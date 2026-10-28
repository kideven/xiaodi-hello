package com.even.gate

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parser for the GitHub mirror of the VPN Gate list
 * (fdciabdul/Vpngate-Scraper-API, json/data.json), used when the
 * official vpngate.net API is unreachable (e.g. blocked direct).
 *
 * Shape: a top-level array whose first object element carries
 * "servers": [ {hostname, ip, score, ping, speed, countrylong,
 * countryshort, numvpnsessions, uptime, totalusers, totaltraffic,
 * logtype, operator, message, openvpn_configdata_base64}, ... ];
 * a second element is the scrape timestamp (epoch millis).
 * All server values are strings in this mirror.
 *
 * Uses org.json, which ships with Android (not available to JVM unit
 * tests, so this parser is exercised on-device only).
 */
object VpnGateJsonParser {

    fun parse(json: String): List<VpnServer> {
        val root = JSONArray(json)
        var serversArray: JSONArray? = null
        for (i in 0 until root.length()) {
            val el = root.optJSONObject(i) ?: continue
            if (el.has("servers")) {
                serversArray = el.getJSONArray("servers")
                break
            }
        }
        val arr = serversArray ?: return emptyList()

        val servers = ArrayList<VpnServer>(arr.length())
        for (i in 0 until arr.length()) {
            val o: JSONObject = arr.optJSONObject(i) ?: continue
            servers.add(
                VpnServer(
                    hostName = o.optString("hostname", ""),
                    ip = o.optString("ip", ""),
                    score = o.optString("score", "").toLongOrNull() ?: 0L,
                    pingMs = o.optString("ping", "").toLongOrNull() ?: -1L,
                    speedBps = o.optString("speed", "").toLongOrNull() ?: 0L,
                    countryLong = o.optString("countrylong", ""),
                    countryShort = o.optString("countryshort", ""),
                    numSessions = o.optString("numvpnsessions", "").toLongOrNull() ?: 0L,
                    uptimeMs = o.optString("uptime", "").toLongOrNull() ?: 0L,
                    totalUsers = o.optString("totalusers", "").toLongOrNull() ?: 0L,
                    totalTraffic = o.optString("totaltraffic", "").toLongOrNull() ?: 0L,
                    logType = o.optString("logtype", ""),
                    operator = o.optString("operator", ""),
                    message = o.optString("message", ""),
                    openVpnConfigBase64 = o.optString("openvpn_configdata_base64", "")
                )
            )
        }
        return servers
    }
}
