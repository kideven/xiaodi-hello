package com.even.gate

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Loopback SOCKS5 server whose outbound path is a VLESS+WS tunnel
 * through the user's private node ("borrowed path"). The integrated
 * OpenVPN core is pointed at 127.0.0.1:11080 as its socks-proxy, so the
 * relay connection rides the private node instead of the direct network.
 */
class GateSocksServer(private val node: VlessNode) {

    companion object {
        const val PORT = 11080

        @Volatile
        private var current: GateSocksServer? = null
        @Volatile
        private var currentNodeKey: String? = null

        /** Start (or restart, if the node changed) the shared server. */
        @Synchronized
        fun ensureRunning(node: VlessNode): GateSocksServer {
            val key = "${node.uuid}@${node.host}:${node.port}${node.wsPath}"
            val existing = current
            if (existing != null && existing.running && currentNodeKey == key) {
                return existing
            }
            existing?.stop()
            val server = GateSocksServer(node)
            server.start()
            current = server
            currentNodeKey = key
            GateDiag.log("借道服务启动，节点 ${node.host}:${node.port}，本地 SOCKS 127.0.0.1:$PORT")
            return server
        }
    }

    @Volatile
    var running: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null

    fun start() {
        val ss = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        running = true
        thread(isDaemon = true, name = "gate-socks-accept") {
            while (running) {
                try {
                    val client = ss.accept()
                    thread(isDaemon = true, name = "gate-socks-conn") {
                        handle(client)
                    }
                } catch (e: Exception) {
                    if (running) {
                        // transient accept failure; keep serving
                        Thread.sleep(50)
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        var tunnel: VlessTunnel? = null
        try {
            client.tcpNoDelay = true
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // Greeting: VER, NMETHODS, METHODS...
            val ver = cin.read()
            if (ver != 5) return
            val nMethods = cin.read()
            if (nMethods < 0) return
            val methods = ByteArray(nMethods)
            readFully(cin, methods)
            cout.write(byteArrayOf(5, 0)) // no auth
            cout.flush()

            // Request: VER, CMD, RSV, ATYP, DST, PORT
            val head = ByteArray(4)
            readFully(cin, head)
            if (head[0].toInt() != 5 || head[1].toInt() != 1) {
                replyError(cout, 7) // command not supported
                return
            }
            val targetHost: String = when (head[3].toInt()) {
                1 -> {
                    val b = ByteArray(4)
                    readFully(cin, b)
                    b.joinToString(".") { (it.toInt() and 0xff).toString() }
                }
                3 -> {
                    val len = cin.read()
                    if (len < 0) return
                    val b = ByteArray(len)
                    readFully(cin, b)
                    String(b, Charsets.UTF_8)
                }
                4 -> {
                    val b = ByteArray(16)
                    readFully(cin, b)
                    InetAddress.getByAddress(b).hostAddress ?: return
                }
                else -> {
                    replyError(cout, 8)
                    return
                }
            }
            val portBytes = ByteArray(2)
            readFully(cin, portBytes)
            val targetPort = (portBytes[0].toInt() and 0xff) shl 8 or
                (portBytes[1].toInt() and 0xff)
            GateDiag.log("SOCKS 请求目标：$targetHost:$targetPort")

            // Borrowed path: dial the relay through the private node.
            val t = VlessTunnel(node)
            tunnel = t
            t.open(targetHost, targetPort)
            GateDiag.log("隧道已建立：$targetHost:$targetPort")

            // Success reply.
            cout.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            cout.flush()

            pumpBothWays(client, cin, cout, t)
        } catch (e: Exception) {
            // Any failure just drops this connection; OpenVPN will retry.
            GateDiag.log("SOCKS 连接异常 ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                tunnel?.close()
            } catch (_: Exception) {
            }
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun pumpBothWays(client: Socket, cin: InputStream, cout: OutputStream,
                             tunnel: VlessTunnel) {
        val upBytes = java.util.concurrent.atomic.AtomicLong(0)
        val downBytes = java.util.concurrent.atomic.AtomicLong(0)
        val up = thread(isDaemon = true, name = "gate-socks-up") {
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = cin.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        tunnel.writeFrame(buf, 0, n)
                        upBytes.addAndGet(n.toLong())
                    }
                }
            } catch (_: Exception) {
            } finally {
                tunnel.close()
            }
        }
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = tunnel.readChunk(buf)
                if (n < 0) break
                if (n > 0) {
                    cout.write(buf, 0, n)
                    cout.flush()
                    downBytes.addAndGet(n.toLong())
                }
            }
        } catch (_: Exception) {
        } finally {
            GateDiag.log("连接结束：上行 ${upBytes.get()}B / 下行 ${downBytes.get()}B")
            try {
                client.close()
            } catch (_: Exception) {
            }
            try {
                up.join(500)
            } catch (_: Exception) {
            }
        }
    }

    private fun replyError(out: OutputStream, code: Int) {
        try {
            out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
        } catch (_: Exception) {
        }
    }

    private fun readFully(inp: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = inp.read(buf, read, buf.size - read)
            if (n < 0) throw IllegalStateException("eof")
            read += n
        }
    }
}
