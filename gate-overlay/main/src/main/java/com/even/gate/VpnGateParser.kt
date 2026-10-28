package com.even.gate

import java.util.Base64

/**
 * One relay server row from the VPN Gate public list API.
 * Field order follows the official CSV header:
 * HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,
 * Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
 */
data class VpnServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    /** Ping in ms; -1 when the API reports "-" (unknown). */
    val pingMs: Long,
    /** Line speed in bits per second, as reported by the API. */
    val speedBps: Long,
    val countryLong: String,
    val countryShort: String,
    val numSessions: Long,
    /** Cumulative uptime in milliseconds, as reported by the API. */
    val uptimeMs: Long,
    val totalUsers: Long,
    val totalTraffic: Long,
    val logType: String,
    val operator: String,
    val message: String,
    val openVpnConfigBase64: String
) {
    val speedMbps: Double get() = speedBps / 1_000_000.0

    fun decodedConfig(): String {
        // The GitHub mirror's values carry a trailing carriage return,
        // which the strict decoder rejects. Strip all whitespace first;
        // fall back to the MIME decoder (ignores non-alphabet chars).
        val cleaned = openVpnConfigBase64.replace(Regex("\\s"), "")
        val bytes = try {
            Base64.getDecoder().decode(cleaned)
        } catch (e: IllegalArgumentException) {
            Base64.getMimeDecoder().decode(openVpnConfigBase64)
        }
        return String(bytes, Charsets.UTF_8)
    }
}

/**
 * Parser for the VPN Gate list API (https://www.vpngate.net/api/iphone/).
 *
 * Format notes:
 * - Lines starting with '*' are marker lines and are skipped.
 * - The line starting with "#HostName" is the header and is skipped.
 * - The Message field may itself contain commas, so parsing is positional:
 *   the first 13 fields are fixed, the LAST field is the Base64 config,
 *   and everything in between is joined back into Message.
 *
 * Pure JVM/Kotlin (java.util.Base64 exists on Android API 26+), so this
 * parser is unit-testable off-device.
 */
object VpnGateParser {

    private const val FIXED_FIELD_COUNT = 13 // HostName .. Operator

    fun parse(csv: String): List<VpnServer> {
        val servers = ArrayList<VpnServer>()
        for (rawLine in csv.split('\n')) {
            val line = rawLine.trimEnd('\r').trim()
            if (line.isEmpty()) continue
            if (line.startsWith("*")) continue
            if (line.startsWith("#")) continue // header row

            val parts = line.split(',')
            // Minimum: 13 fixed + 1 message (possibly empty) + 1 config = 15 parts.
            // Tolerate 14 parts (message column entirely absent) defensively.
            if (parts.size < FIXED_FIELD_COUNT + 1) continue

            val config = parts[parts.size - 1]
            val message = if (parts.size > FIXED_FIELD_COUNT + 1) {
                parts.subList(FIXED_FIELD_COUNT, parts.size - 1).joinToString(",")
            } else {
                ""
            }

            servers.add(
                VpnServer(
                    hostName = parts[0],
                    ip = parts[1],
                    score = parts[2].toLongOrNull() ?: 0L,
                    pingMs = parts[3].toLongOrNull() ?: -1L,
                    speedBps = parts[4].toLongOrNull() ?: 0L,
                    countryLong = parts[5],
                    countryShort = parts[6],
                    numSessions = parts[7].toLongOrNull() ?: 0L,
                    uptimeMs = parts[8].toLongOrNull() ?: 0L,
                    totalUsers = parts[9].toLongOrNull() ?: 0L,
                    totalTraffic = parts[10].toLongOrNull() ?: 0L,
                    logType = parts[11],
                    operator = parts[12],
                    message = message,
                    openVpnConfigBase64 = config
                )
            )
        }
        return servers
    }
}
