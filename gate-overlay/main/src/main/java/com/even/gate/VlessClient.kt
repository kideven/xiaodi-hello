package com.even.gate

import android.util.Base64
import de.blinkt.openvpn.core.OpenVPNService
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A parsed vless:// share link for one of the user's private
 * Cloudflare Pages VLESS+WS+TLS nodes.
 */
data class VlessNode(
    val uuid: String,
    val host: String,
    val port: Int,
    val wsPath: String,
    val sni: String
) {
    companion object {
        /** vless://uuid@host:port?security=tls&type=ws&path=%2F&sni=host#name */
        fun parseLink(link: String): VlessNode? {
            val text = link.trim()
            if (!text.startsWith("vless://")) return null
            return try {
                val rest = text.removePrefix("vless://").substringBefore('#')
                val q = rest.indexOf('?')
                val authority = if (q >= 0) rest.substring(0, q) else rest
                val query = if (q >= 0) rest.substring(q + 1) else ""
                val at = authority.lastIndexOf('@')
                if (at <= 0) return null
                val uuid = authority.substring(0, at)
                UUID.fromString(uuid) // validate
                val hostPort = authority.substring(at + 1)
                val colon = hostPort.lastIndexOf(':')
                val host: String
                val port: Int
                if (colon > 0) {
                    host = hostPort.substring(0, colon).trim('[', ']')
                    port = hostPort.substring(colon + 1).toIntOrNull() ?: 443
                } else {
                    host = hostPort
                    port = 443
                }
                val params = HashMap<String, String>()
                for (pair in query.split('&')) {
                    val eq = pair.indexOf('=')
                    if (eq > 0) {
                        params[pair.substring(0, eq)] =
                            URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    }
                }
                val path = params["path"]?.takeIf { it.isNotBlank() } ?: "/"
                val sni = params["sni"] ?: params["host"] ?: host
                VlessNode(uuid, host, port, path, sni)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Salvage a node from a pasted Clash proxy entry (the user may only
         * have the node in Clash YAML form). Extracts uuid/server/port and,
         * when present, ws-opts path and servername via simple regexes.
         */
        fun parseClashEntry(text: String): VlessNode? {
            fun grab(pattern: String): String? =
                Regex(pattern, RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim()
                    ?.trim('"', '\'', ',')
            val uuid = grab("""uuid:\s*([0-9a-fA-F-]{36})""") ?: return null
            val server = grab("""server:\s*([A-Za-z0-9.-]+)""") ?: return null
            val port = grab("""port:\s*(\d+)""")?.toIntOrNull() ?: 443
            val path = grab("""path:\s*"?([^"\n]+)"?""")?.takeIf { it.isNotBlank() } ?: "/"
            val sni = grab("""servername:\s*([A-Za-z0-9.-]+)""") ?: server
            return try {
                UUID.fromString(uuid)
                VlessNode(uuid, server, port, path, sni)
            } catch (e: Exception) {
                null
            }
        }

        /** Canonical share link for storage, whatever the input format was. */
        fun VlessNode.toLink(): String {
            val encPath = java.net.URLEncoder.encode(wsPath, "UTF-8")
            return "vless://$uuid@$host:$port?encryption=none&security=tls" +
                "&sni=$sni&type=ws&host=$host&path=$encPath#GateNode"
        }
    }
}

/**
 * One VLESS-over-WebSocket-over-TLS tunnel to a private node.
 * Wire format verified against the user's own node monitor:
 * first WS binary frame starts with the VLESS request header,
 * first inbound bytes are the 2-byte VLESS response header.
 */
class VlessTunnel(private val node: VlessNode) {

    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val writeLock = Object()
    private val maskRandom = java.security.SecureRandom()
    private var responseHeaderPending = true
    private var targetHost: String = ""
    private var targetPort: Int = 0
    private var headerSent = false
    private var firstFrameLogged = false

    /** Connect TLS + WS upgrade. The VLESS request header is prepended to
     *  the first data frame in writeFrame (same wire shape as the verified
     *  node-monitor client, which sends header + first payload together). */
    fun open(targetHost: String, targetPort: Int) {
        this.targetHost = targetHost
        this.targetPort = targetPort
        try {
            openInternal(targetHost, targetPort)
        } catch (e: Exception) {
            GateDiag.log("VLESS 失败 ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun openInternal(targetHost: String, targetPort: Int) {
        GateDiag.log("VLESS 连接节点 ${node.host}:${node.port}（SNI=${node.sni} 路径=${node.wsPath}）目标 $targetHost:$targetPort")
        val raw = Socket()
        // Keep the underlay socket out of our own VPN tunnel once it is up.
        try {
            OpenVPNService.gateProtect(raw)
        } catch (_: Exception) {
        }
        raw.tcpNoDelay = true
        raw.connect(InetSocketAddress(node.host, node.port), 20_000)
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        val tls = factory.createSocket(raw, node.sni, node.port, true) as SSLSocket
        tls.sslParameters = tls.sslParameters.apply {
            serverNames = listOf(SNIHostName(node.sni))
        }
        tls.startHandshake()
        GateDiag.log("TLS 握手完成")
        socket = tls
        val inp = BufferedInputStream(tls.inputStream, 32 * 1024)
        input = inp
        output = tls.outputStream

        // WebSocket upgrade
        val key = Base64.encodeToString(ByteArray(16).also { maskRandom.nextBytes(it) },
            Base64.NO_WRAP)
        val req = StringBuilder()
        req.append("GET ").append(node.wsPath).append(" HTTP/1.1\r\n")
        req.append("Host: ").append(node.host).append("\r\n")
        req.append("Upgrade: websocket\r\n")
        req.append("Connection: Upgrade\r\n")
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n")
        req.append("Sec-WebSocket-Version: 13\r\n")
        req.append("User-Agent: GateClient/2.0 (Android)\r\n")
        req.append("\r\n")
        output!!.write(req.toString().toByteArray(Charsets.US_ASCII))
        output!!.flush()

        val statusLine = readHttpLine(inp)
        GateDiag.log("WS 升级响应：$statusLine")
        if (statusLine == null || !statusLine.contains("101")) {
            throw IOException("WS upgrade failed: $statusLine")
        }
        while (true) {
            val line = readHttpLine(inp) ?: throw IOException("WS upgrade truncated")
            if (line.isEmpty()) break
        }
        GateDiag.log("WS 升级成功，等待首个数据帧合并 VLESS 头发送")
    }

    private fun readHttpLine(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var prev = -1
        while (true) {
            val b = inp.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("US-ASCII")
            if (prev == '\r'.code && b == '\n'.code) {
                val bytes = buf.toByteArray()
                return String(bytes, 0, bytes.size - 1, Charsets.US_ASCII)
            }
            buf.write(b)
            prev = b
        }
    }

    private fun buildRequestHeader(targetHost: String, targetPort: Int): ByteArray {
        val uuid = UUID.fromString(node.uuid)
        val out = ByteArrayOutputStream()
        out.write(0) // version
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        for (i in 7 downTo 0) out.write((msb shr (i * 8)).toInt())
        for (i in 7 downTo 0) out.write((lsb shr (i * 8)).toInt())
        out.write(0) // addons length
        out.write(1) // command: TCP
        out.write((targetPort shr 8) and 0xff)
        out.write(targetPort and 0xff)
        val ipv4 = parseIpv4(targetHost)
        val ipv6 = if (ipv4 == null) parseIpv6(targetHost) else null
        when {
            ipv4 != null -> {
                out.write(1)
                out.write(ipv4)
            }
            ipv6 != null -> {
                out.write(3)
                out.write(ipv6)
            }
            else -> {
                val hb = targetHost.toByteArray(Charsets.UTF_8)
                out.write(2)
                out.write(hb.size)
                out.write(hb)
            }
        }
        return out.toByteArray()
    }

    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val v = parts[i].toIntOrNull() ?: return null
            if (v !in 0..255 || parts[i].isEmpty()) return null
            bytes[i] = v.toByte()
        }
        return bytes
    }

    private fun parseIpv6(host: String): ByteArray? {
        if (!host.contains(':')) return null
        return try {
            val addr = InetAddress.getByName(host)
            addr.address.takeIf { it.size == 16 }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Send payload through the tunnel. The first call prepends the VLESS
     * request header to the payload in a single frame (header + first
     * payload together, the shape the reference client uses).
     */
    fun writeFrame(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!headerSent) {
            val header = buildRequestHeader(targetHost, targetPort)
            val merged = ByteArray(header.size + length)
            System.arraycopy(header, 0, merged, 0, header.size)
            System.arraycopy(data, offset, merged, header.size, length)
            headerSent = true
            GateDiag.log("首帧发出：VLESS 头 ${header.size}B + 数据 ${length}B，数据开头 ${GateDiag.describeBytes(data, offset, length)}")
            sendRawFrame(merged, 0, merged.size)
            return
        }
        sendRawFrame(data, offset, length)
    }

    /** Send one binary frame (client frames are masked, per RFC 6455). */
    private fun sendRawFrame(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val out = output ?: throw IOException("tunnel closed")
        synchronized(writeLock) {
            val mask = ByteArray(4).also { maskRandom.nextBytes(it) }
            val header = ByteArrayOutputStream()
            header.write(0x82) // FIN + binary
            when {
                length < 126 -> header.write(0x80 or length)
                length <= 0xffff -> {
                    header.write(0x80 or 126)
                    header.write((length shr 8) and 0xff)
                    header.write(length and 0xff)
                }
                else -> {
                    header.write(0x80 or 127)
                    val l = length.toLong()
                    for (i in 7 downTo 0) header.write((l shr (i * 8)).toInt())
                }
            }
            header.write(mask)
            out.write(header.toByteArray())
            val masked = ByteArray(length)
            for (i in 0 until length) {
                masked[i] = (data[offset + i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            out.write(masked)
            out.flush()
        }
    }

    private fun writeControlFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: return
        try {
            synchronized(writeLock) {
                val mask = ByteArray(4).also { maskRandom.nextBytes(it) }
                out.write(0x80 or opcode)
                out.write(0x80 or payload.size)
                out.write(mask)
                val masked = ByteArray(payload.size)
                for (i in payload.indices) {
                    masked[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
                out.write(masked)
                out.flush()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Read the next chunk of tunnel payload (VLESS response header stripped).
     * Returns null on clean close.
     */
    fun readChunk(buffer: ByteArray): Int {
        val inp = input ?: throw IOException("tunnel closed")
        while (true) {
            val frame = readFrame(inp) ?: return -1
            when (frame.opcode) {
                0x8 -> return -1 // close
                0x9 -> writeControlFrame(0xA, frame.payload) // ping -> pong
                0xA -> continue // pong, ignore
                else -> {
                    var payload = frame.payload
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        GateDiag.log("首个回程帧 opcode=${frame.opcode} ${payload.size}B 开头 ${GateDiag.describeBytes(payload)}")
                    }
                    if (responseHeaderPending) {
                        if (payload.size < 2) continue
                        val skip = 2 + (payload[1].toInt() and 0xff)
                        if (payload.size <= skip) {
                            responseHeaderPending = false
                            continue
                        }
                        payload = payload.copyOfRange(skip, payload.size)
                        responseHeaderPending = false
                        GateDiag.log("剥掉 VLESS 响应头 ${skip}B 后，开头 ${GateDiag.describeBytes(payload)}")
                    }
                    if (payload.isEmpty()) continue
                    val n = minOf(payload.size, buffer.size)
                    System.arraycopy(payload, 0, buffer, 0, n)
                    if (n < payload.size) {
                        pending = payload.copyOfRange(n, payload.size)
                    }
                    return n
                }
            }
        }
    }

    private var pending: ByteArray? = null

    private data class Frame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(inp: InputStream): Frame? {
        pending?.let {
            pending = null
            return Frame(0x2, it)
        }
        val b0 = inp.read()
        if (b0 < 0) return null
        val opcode = b0 and 0x0f
        val b1 = inp.read()
        if (b1 < 0) return null
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) {
            len = ((inp.read() and 0xff) shl 8 or (inp.read() and 0xff)).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = len shl 8 or (inp.read() and 0xff).toLong() }
        }
        val masked = b1 and 0x80 != 0
        val mask = if (masked) ByteArray(4) { inp.read().toByte() } else null
        val payload = ByteArray(len.toInt())
        var read = 0
        while (read < payload.size) {
            val n = inp.read(payload, read, payload.size - read)
            if (n < 0) return null
            read += n
        }
        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
        }
        return Frame(opcode, payload)
    }

    fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
