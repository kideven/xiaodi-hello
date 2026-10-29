package com.even.gate

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory diagnostic log for the borrowed path. Shown in the app's
 * "借道诊断" dialog so connection problems can be pinned to one segment
 * (SOCKS target / VLESS dial / first bytes back) from a screenshot.
 */
object GateDiag {
    private const val MAX_LINES = 200
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(message: String) {
        lines.addLast("${fmt.format(Date())} $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String =
        if (lines.isEmpty()) "（暂无记录）" else lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }

    /** "48 54 54 50 (HTTP)" style rendering of the first bytes of a payload. */
    fun describeBytes(data: ByteArray, offset: Int = 0, length: Int = data.size): String {
        val n = minOf(length, 16)
        if (n <= 0) return "(空)"
        val hex = StringBuilder()
        val ascii = StringBuilder()
        for (i in 0 until n) {
            val b = data[offset + i].toInt() and 0xff
            if (i > 0) hex.append(' ')
            hex.append("%02x".format(b))
            ascii.append(if (b in 0x20..0x7e) b.toChar() else '.')
        }
        return "$hex ($ascii)"
    }
}
