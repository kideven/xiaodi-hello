package com.even.gate

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.Connection
import de.blinkt.openvpn.core.ProfileManager
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Gate list: fetches the VPN Gate public relay list, lets the user pick a
 * relay, and connects through the integrated OpenVPN engine (ics-openvpn)
 * via a temporary profile + LaunchVPN. No external OpenVPN app involved.
 */
class GateListActivity : Activity() {

    companion object {
        const val ALL_COUNTRIES = "全部"
        private const val CACHE_FILE = "server_list_cache.txt"
        private const val PREF_BORROW_ENABLED = "borrow_enabled"
        private const val PREF_VLESS_LINK = "vless_link"
    }

    private data class Source(
        val label: String,
        val url: String,
        val format: String, // "csv" or "json"
        val parser: (String) -> List<VpnServer>
    )

    private val sources = listOf(
        Source(
            "官方源", "https://www.vpngate.net/api/iphone/", "csv"
        ) { VpnGateParser.parse(it) },
        Source(
            "镜像源", "https://cdn.jsdelivr.net/gh/fdciabdul/Vpngate-Scraper-API@main/json/data.json", "json"
        ) { VpnGateJsonParser.parse(it) },
        Source(
            "镜像源(Fastly)", "https://fastly.jsdelivr.net/gh/fdciabdul/Vpngate-Scraper-API@main/json/data.json", "json"
        ) { VpnGateJsonParser.parse(it) },
        Source(
            "镜像源(Gcore)", "https://gcore.jsdelivr.net/gh/fdciabdul/Vpngate-Scraper-API@main/json/data.json", "json"
        ) { VpnGateJsonParser.parse(it) },
        Source(
            "镜像源(备用CDN)", "https://testingcf.jsdelivr.net/gh/fdciabdul/Vpngate-Scraper-API@main/json/data.json", "json"
        ) { VpnGateJsonParser.parse(it) },
        Source(
            "镜像源(GitHub直连)", "https://raw.githubusercontent.com/fdciabdul/Vpngate-Scraper-API/main/json/data.json", "json"
        ) { VpnGateJsonParser.parse(it) }
    )

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var spinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var refreshButton: Button
    private lateinit var vpnUiButton: Button
    private lateinit var borrowCheck: CheckBox
    private lateinit var borrowSettingsButton: Button
    private lateinit var listView: ListView

    private var allServers: List<VpnServer> = emptyList()
    private var shownListTime: Long = 0L
    private val listAdapter = ServerAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.gate_activity_main)

        spinner = findViewById(R.id.countrySpinner)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        retryButton = findViewById(R.id.retryButton)
        refreshButton = findViewById(R.id.refreshButton)
        vpnUiButton = findViewById(R.id.vpnUiButton)
        borrowCheck = findViewById(R.id.borrowCheck)
        borrowSettingsButton = findViewById(R.id.borrowSettingsButton)
        listView = findViewById(R.id.serverList)

        val prefs = getSharedPreferences("gate_prefs", Context.MODE_PRIVATE)
        borrowCheck.isChecked = prefs.getBoolean(PREF_BORROW_ENABLED, true)
        borrowCheck.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_BORROW_ENABLED, checked).apply()
        }
        borrowSettingsButton.setOnClickListener { showBorrowSettings() }

        listView.adapter = listAdapter
        listView.setOnItemClickListener { _, _, position, _ ->
            listAdapter.getItem(position)?.let { showDetail(it) }
        }
        retryButton.setOnClickListener { loadServers() }
        refreshButton.setOnClickListener { loadServers() }
        vpnUiButton.setOnClickListener { openOpenVpnUi() }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                applyFilter()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val cached = readCache()
        if (cached != null) {
            onLoaded(cached.servers, "缓存", cached.savedAtMillis)
        }
        loadServers()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun openOpenVpnUi() {
        try {
            startActivity(
                Intent().setClassName(this, "de.blinkt.openvpn.activities.MainActivity")
            )
        } catch (e: Exception) {
            Toast.makeText(this, "打开失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadServers() {
        progressBar.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        refreshButton.isEnabled = false
        if (allServers.isEmpty()) {
            statusText.text = "正在加载服务器列表…"
        }

        executor.execute {
            var lastError: Exception? = null
            for ((index, source) in orderedSources()) {
                try {
                    val payload = fetchText(source.url)
                    val servers = source.parser(payload)
                        .sortedByDescending { it.speedBps }
                    if (servers.isEmpty()) {
                        throw IllegalStateException("${source.label}返回空列表")
                    }
                    val now = System.currentTimeMillis()
                    writeCache(source, payload, now)
                    getSharedPreferences("gate_prefs", Context.MODE_PRIVATE)
                        .edit().putInt("last_source", index).apply()
                    runOnUiThread { onLoaded(servers, source.label, now) }
                    return@execute
                } catch (e: Exception) {
                    lastError = e
                }
            }
            val err = lastError ?: IllegalStateException("未知错误")
            runOnUiThread { onLoadError(err) }
        }
    }

    private fun orderedSources(): List<Pair<Int, Source>> {
        val last = getSharedPreferences("gate_prefs", Context.MODE_PRIVATE)
            .getInt("last_source", 0)
        val indexed = sources.withIndex().map { it.index to it.value }
        if (last !in sources.indices) return indexed
        return listOf(indexed[last]) + indexed.filter { it.first != last }
    }

    private fun fetchText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "GateClient/1.0-integrated (Android)")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $code")
            }
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private data class CachedList(
        val servers: List<VpnServer>,
        val savedAtMillis: Long
    )

    private fun writeCache(source: Source, payload: String, savedAtMillis: Long) {
        try {
            val file = File(filesDir, CACHE_FILE)
            file.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write("${source.format}\t$savedAtMillis")
                w.newLine()
                w.write(payload)
            }
        } catch (_: Exception) {
        }
    }

    private fun readCache(): CachedList? {
        return try {
            val file = File(filesDir, CACHE_FILE)
            if (!file.exists()) return null
            val text = file.readText(Charsets.UTF_8)
            val nl = text.indexOf('\n')
            if (nl < 0) return null
            val meta = text.substring(0, nl).split('\t')
            if (meta.size < 2) return null
            val format = meta[0]
            val savedAt = meta[1].toLongOrNull() ?: return null
            val payload = text.substring(nl + 1)
            val servers = when (format) {
                "csv" -> VpnGateParser.parse(payload)
                "json" -> VpnGateJsonParser.parse(payload)
                else -> return null
            }.sortedByDescending { it.speedBps }
            if (servers.isEmpty()) null else CachedList(servers, savedAt)
        } catch (_: Exception) {
            null
        }
    }

    private fun onLoaded(servers: List<VpnServer>, sourceLabel: String, listTimeMillis: Long) {
        progressBar.visibility = View.GONE
        refreshButton.isEnabled = true
        retryButton.visibility = View.GONE
        allServers = servers
        shownListTime = listTimeMillis

        val countries = servers.map { it.countryLong }.distinct().sorted()
        val spinnerItems = listOf(ALL_COUNTRIES) + countries
        val previous = spinner.selectedItem as? String
        spinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, spinnerItems
        )
        if (previous != null && spinnerItems.contains(previous)) {
            spinner.setSelection(spinnerItems.indexOf(previous))
        }

        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(listTimeMillis))
        statusText.text = "共 ${servers.size} 台 · $sourceLabel · 更新于 $time · 按速度排序"
        applyFilter()
    }

    private fun onLoadError(e: Exception) {
        progressBar.visibility = View.GONE
        refreshButton.isEnabled = true
        if (allServers.isNotEmpty()) {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(shownListTime))
            statusText.text = "刷新失败（${e.message ?: "网络错误"}），显示的是 $time 的列表"
            retryButton.visibility = View.GONE
        } else {
            retryButton.visibility = View.VISIBLE
            statusText.text = "加载失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun applyFilter() {
        val selected = spinner.selectedItem as? String ?: ALL_COUNTRIES
        val filtered = if (selected == ALL_COUNTRIES) {
            allServers
        } else {
            allServers.filter { it.countryLong == selected }
        }
        listAdapter.submit(filtered)
    }

    private fun showDetail(server: VpnServer) {
        val config = try {
            server.decodedConfig()
        } catch (e: Exception) {
            "(配置解码失败：${e.message})"
        }
        val text = buildString {
            appendLine("主机名：${server.hostName}")
            appendLine("IP：${server.ip}")
            appendLine("国家：${server.countryLong} (${server.countryShort})")
            appendLine("得分：${server.score}")
            appendLine("Ping：${if (server.pingMs >= 0) "${server.pingMs} ms" else "-"}")
            appendLine("速度：${"%.2f".format(server.speedMbps)} Mbps (${server.speedBps} bps)")
            appendLine("当前会话数：${server.numSessions}")
            appendLine("累计在线：${formatUptime(server.uptimeMs)}")
            appendLine("累计用户数：${server.totalUsers}")
            appendLine("累计流量：${server.totalTraffic} bytes")
            appendLine("日志策略：${server.logType}")
            appendLine("运营者：${server.operator}")
            appendLine("留言：${server.message}")
            appendLine()
            appendLine("—— OpenVPN 配置 ——")
            append(config)
        }

        val view = LayoutInflater.from(this).inflate(R.layout.gate_dialog_detail, null)
        view.findViewById<TextView>(R.id.detailText).text = text

        AlertDialog.Builder(this)
            .setTitle("${server.countryLong} · ${server.ip}")
            .setView(view)
            .setPositiveButton("连接") { _, _ -> connectTo(server, config) }
            .setNeutralButton("复制配置") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("OpenVPN Config", config))
                Toast.makeText(this, "配置已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showBorrowSettings() {
        val prefs = getSharedPreferences("gate_prefs", Context.MODE_PRIVATE)
        val input = EditText(this).apply {
            hint = "vless://…"
            setText(prefs.getString(PREF_VLESS_LINK, "") ?: "")
            setSingleLine(false)
            maxLines = 4
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(TextView(context).apply {
                text = "最省事：点下面的「从订阅自动获取」，App 自己去你的私人订阅里取一个节点，什么都不用粘。\n\n也可以手动粘贴：vless:// 链接，或者 Clash 配置里某个节点的那一段文字（含 uuid、server 的那几行），都能认。借道开启时，连接中继会先经这个节点出去，绕开直连干扰。设置一次，之后一直有效。"
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("借道设置")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val raw = input.text.toString().trim()
                val node = VlessNode.parseLink(raw) ?: VlessNode.parseClashEntry(raw)
                if (node == null) {
                    Toast.makeText(this, "没认出这个格式，没有保存", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit()
                        .putString(PREF_VLESS_LINK, with(VlessNode) { node.toLink() })
                        .putBoolean(PREF_BORROW_ENABLED, true)
                        .apply()
                    borrowCheck.isChecked = true
                    Toast.makeText(this, "借道节点已保存：${node.host}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("从订阅自动获取") { _, _ -> fetchBorrowLink() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Pull the first private-node link from the user's own subscription. */
    private fun fetchBorrowLink() {
        Toast.makeText(this, "正在从你的私人订阅获取节点…", Toast.LENGTH_SHORT).show()
        executor.execute {
            var node: VlessNode? = null
            var lastError: Exception? = null
            val candidates = mutableListOf<String>()
            try {
                candidates += fetchText("https://private-sub.pages.dev/plain")
            } catch (e: Exception) {
                lastError = e
            }
            if (extractVless(candidates.firstOrNull()) == null) {
                try {
                    val b64 = fetchText("https://private-sub.pages.dev/sub")
                    candidates += String(
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT),
                        Charsets.UTF_8
                    )
                } catch (e: Exception) {
                    lastError = e
                }
            }
            for (text in candidates) {
                val link = extractVless(text) ?: continue
                node = VlessNode.parseLink(link)
                if (node != null) break
            }
            val found = node
            runOnUiThread {
                if (found != null) {
                    getSharedPreferences("gate_prefs", Context.MODE_PRIVATE).edit()
                        .putString(PREF_VLESS_LINK, with(VlessNode) { found.toLink() })
                        .putBoolean(PREF_BORROW_ENABLED, true)
                        .apply()
                    borrowCheck.isChecked = true
                    Toast.makeText(
                        this, "已自动获取借道节点：${found.host}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "自动获取失败：${lastError?.message ?: "订阅里没找到节点"}，可以手动粘贴",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun extractVless(text: String?): String? {
        if (text == null) return null
        for (line in text.split('\n', ' ', '\t', '\r')) {
            val token = line.trim()
            if (token.startsWith("vless://")) return token
        }
        return null
    }

    /**
     * Connect through the integrated engine: parse the inline config into a
     * temporary VpnProfile and hand it to LaunchVPN (which runs the system
     * VPN-permission flow and starts OpenVPNService).
     */
    private fun connectTo(server: VpnServer, config: String) {
        if (config.startsWith("(")) {
            Toast.makeText(this, "配置解码失败，无法连接", Toast.LENGTH_SHORT).show()
            return
        }
        // Borrowed path: route the relay connection through the private
        // node first (local SOCKS server backed by a VLESS+WS tunnel).
        var borrowNode: VlessNode? = null
        if (borrowCheck.isChecked) {
            val link = getSharedPreferences("gate_prefs", Context.MODE_PRIVATE)
                .getString(PREF_VLESS_LINK, "") ?: ""
            borrowNode = VlessNode.parseLink(link)
            if (borrowNode == null) {
                Toast.makeText(
                    this, "借道模式已开启，但还没填私人节点链接",
                    Toast.LENGTH_LONG
                ).show()
                showBorrowSettings()
                return
            }
            try {
                GateSocksServer.ensureRunning(borrowNode)
            } catch (e: Exception) {
                Toast.makeText(this, "借道服务启动失败：${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }

        val effectiveConfig = if (borrowNode != null) {
            config + "\nsocks-proxy 127.0.0.1 ${GateSocksServer.PORT}\n"
        } else {
            config
        }
        try {
            val cp = ConfigParser()
            cp.parseConfig(StringReader(effectiveConfig))
            val vp = cp.convertProfile()
            vp.mName = "Gate ${server.countryShort} ${server.ip}"
            if (borrowNode != null) {
                for (c in vp.mConnections) {
                    c.mProxyType = Connection.ProxyType.SOCKS5
                    c.mProxyName = "127.0.0.1"
                    c.mProxyPort = GateSocksServer.PORT.toString()
                }
            }
            val check = vp.checkProfile(this)
            if (check != R.string.no_error_found) {
                Toast.makeText(this, "配置不可用：${getString(check)}", Toast.LENGTH_LONG).show()
                return
            }
            ProfileManager.setTemporaryProfile(this, vp)
            val intent = Intent(Intent.ACTION_MAIN).apply {
                setClass(this@GateListActivity, LaunchVPN::class.java)
                putExtra(LaunchVPN.EXTRA_KEY, vp.getUUIDString())
                putExtra(LaunchVPN.EXTRA_NAME, vp.mName)
                putExtra(LaunchVPN.EXTRA_HIDELOG, false)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "连接失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatUptime(ms: Long): String {
        if (ms <= 0) return "-"
        val totalHours = ms / 3_600_000
        val days = totalHours / 24
        val hours = totalHours % 24
        return if (days > 0) "${days} 天 ${hours} 小时" else "${hours} 小时"
    }

    private inner class ServerAdapter : BaseAdapter() {
        private var items: List<VpnServer> = emptyList()

        fun submit(list: List<VpnServer>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): VpnServer? = items.getOrNull(position)
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.gate_list_item_server, parent, false)
            val s = items[position]
            view.findViewById<TextView>(R.id.line1).text =
                "${s.countryLong} (${s.countryShort}) · ${s.ip}"
            view.findViewById<TextView>(R.id.line2).text =
                "速度 ${"%.2f".format(s.speedMbps)} Mbps · " +
                    "Ping ${if (s.pingMs >= 0) "${s.pingMs} ms" else "-"} · " +
                    "会话 ${s.numSessions}"
            return view
        }
    }
}
