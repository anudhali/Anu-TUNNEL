package com.anutunnel.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val bg = Color.rgb(9, 6, 20)
    private val panel = Color.rgb(25, 17, 43)
    private val purple = Color.rgb(174, 55, 245)
    private val white = Color.WHITE
    private lateinit var store: ConfigStore
    private lateinit var root: LinearLayout
    private lateinit var activeContent: LinearLayout
    private var current: TunnelConfig? = null
    private var selectedMode = "Smart Auto Mode"
    private var isHomeScreen = false
    private var pendingExport = ""
    private val prefs by lazy { getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE) }
    private val logs by lazy { getSharedPreferences("anu_tunnel_logs", MODE_PRIVATE) }
    private val reqImport = 702
    private val reqExport = 703
    private val reqVpn = 704
    private var statusLabel: TextView? = null
    private var statsLabel: TextView? = null
    private var lastStatsAt = 0L
    private var lastUploaded = 0L
    private var lastDownloaded = 0L
    private val tunnelStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AnuVpnService.ACTION_STATS) {
                val uploaded = intent.getLongExtra("uploaded", 0L)
                val downloaded = intent.getLongExtra("downloaded", 0L)
                val ping = intent.getLongExtra("ping_ms", -1L)
                val now = SystemClock.elapsedRealtime()
                val elapsed = (now - lastStatsAt).coerceAtLeast(1L)
                val upRate = if (lastStatsAt == 0L) 0L else ((uploaded - lastUploaded).coerceAtLeast(0L) * 1000L / elapsed)
                val downRate = if (lastStatsAt == 0L) 0L else ((downloaded - lastDownloaded).coerceAtLeast(0L) * 1000L / elapsed)
                lastStatsAt = now; lastUploaded = uploaded; lastDownloaded = downloaded
                statsLabel?.text = "↑ ${formatRate(upRate)}   ↓ ${formatRate(downRate)}\nSent ${formatBytes(uploaded)} · Received ${formatBytes(downloaded)} · HTTP Ping ${if (ping >= 0) "$ping ms" else "—"}"
                return
            }
            val state = intent?.getStringExtra("state") ?: return
            val detail = intent.getStringExtra("detail") ?: ""
            statusLabel?.text = "● $state\n$detail"
            statusLabel?.setTextColor(when (state) { "CONNECTED" -> Color.rgb(70, 230, 140); "ERROR" -> Color.RED; else -> Color.LTGRAY })
            addLog("$state: $detail")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        if (store.all().isEmpty()) {
            val initial = TunnelConfig()
            store.upsert(initial)
            store.select(initial.id)
        }
        current = store.all().firstOrNull { it.id == store.selectedId() } ?: store.all().firstOrNull()
        selectedMode = prefs.getString("mode", "Smart Auto Mode") ?: "Smart Auto Mode"
        if (selectedMode == "BDIX Mode") {
            selectedMode = "Global Mode"
            prefs.edit().putString("mode", selectedMode).apply()
        }
        showHome()
    }

    private fun base(title: String): LinearLayout {
        isHomeScreen = false
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(16, 12, 16, 12)
        }
        val header = TextView(this).apply {
            text = title
            textSize = 24f
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(4, 10, 4, 18)
        }
        root.addView(header)
        val scroll = ScrollView(this)
        activeContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
        }
        scroll.addView(activeContent)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        return activeContent
    }

    private fun label(text: String, size: Float = 15f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(white)
        setPadding(4, 7, 4, 7)
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(14, 12, 14, 12)
        setBackgroundColor(panel)
    }

    private fun addCard(view: View) {
        activeContent.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        setTextColor(white)
        setBackgroundColor(purple)
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun actionGrid(container: LinearLayout, actions: List<Pair<String, () -> Unit>>) {
        actions.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (title, action) ->
                val b = button(title, action)
                val lp = LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                row.addView(b, lp)
            }
            if (pair.size == 1) row.addView(Space(this), LinearLayout.LayoutParams(0, dp(54), 1f))
            container.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun field(title: String, value: String, hint: String = ""): Pair<LinearLayout, EditText> {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(label(title, 13f))
        val edit = EditText(this).apply {
            setText(value)
            setTextColor(white)
            setHintTextColor(Color.LTGRAY)
            setHint(hint)
            textSize = 15f
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.rgb(12, 9, 23))
        }
        box.addView(edit, LinearLayout.LayoutParams(-1, -2))
        return box to edit
    }

    private fun showHome() {
        isHomeScreen = true
        val c = base(" ")
        isHomeScreen = true
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(14)) }
        val menuButton = TextView(this).apply {
            text = "☰"; textSize = 30f; gravity = Gravity.CENTER; setTextColor(white)
            setPadding(dp(10), dp(4), dp(18), dp(4)); contentDescription = "Open navigation menu"
            setOnClickListener { openHomeMenu(this) }
        }
        top.addView(menuButton, LinearLayout.LayoutParams(dp(58), dp(54)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label("ANU TUNNEL", 21f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, 0) })
        heading.addView(label("SECURE PRIVATE CONNECTION", 10f).apply { setTextColor(Color.LTGRAY); setPadding(0, 0, 0, 0) })
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(top)

        val statePrefs = getSharedPreferences("anu_tunnel_state", MODE_PRIVATE)
        val state = statePrefs.getString("state", "DISCONNECTED") ?: "DISCONNECTED"
        val connected = state == "CONNECTED"
        val busy = state == "CONNECTING" || state == "RECONNECTING"
        val statusCard = card().apply { gravity = Gravity.CENTER; setPadding(dp(18), dp(20), dp(18), dp(20)) }
        statusCard.addView(label("PRIVATE CONNECTION", 12f).apply { gravity = Gravity.CENTER; setTextColor(Color.LTGRAY) })
        val connectButton = TextView(this).apply {
            text = if (connected) "ON" else if (busy) "…" else "GO"
            textSize = 30f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (connected) Color.rgb(28, 155, 105) else Color.rgb(35, 105, 210))
                setStroke(dp(3), if (connected) Color.rgb(104, 235, 180) else Color.rgb(105, 165, 255))
            }
            elevation = dp(8).toFloat(); isEnabled = !busy
            setOnClickListener {
                if (connected) {
                    startService(Intent(this@MainActivity, AnuVpnService::class.java).setAction(AnuVpnService.ACTION_DISCONNECT))
                    toast("Disconnect requested")
                } else connectNotice()
            }
            contentDescription = if (connected) "Disconnect VPN" else "Connect VPN"
        }
        val circle = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(18)) }
        circle.addView(connectButton, LinearLayout.LayoutParams(dp(148), dp(148)))
        statusCard.addView(circle)
        statusLabel = label("$state\n${statePrefs.getString("detail", "Ready")}", 16f).apply {
            gravity = Gravity.CENTER
            setTextColor(if (connected) Color.rgb(90, 235, 160) else if (state == "ERROR") Color.RED else white)
        }
        statusCard.addView(statusLabel)
        statusCard.addView(label("Tap the circle to ${if (connected) "disconnect" else "connect"}", 11f).apply {
            gravity = Gravity.CENTER; setTextColor(Color.LTGRAY)
        })
        statsLabel = label("↑ 0 KB/s     ↓ 0 KB/s\nTraffic statistics update while connected", 12f).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14), 0, 0)
        }
        statusCard.addView(statsLabel)
        addCard(statusCard)

        val cfg = current ?: store.all().firstOrNull()
        val summary = card()
        summary.addView(label("ACTIVE CONFIGURATION", 11f).apply { setTextColor(Color.LTGRAY) })
        summary.addView(label(cfg?.name ?: "No configuration", 18f).apply { typeface = Typeface.DEFAULT_BOLD })
        summary.addView(label(cfg?.let {
            "${it.connectionType}  •  DNS ${it.udpDns}\nSNI ${it.serverName}\nSSH account: " +
                if (it.account.substringBefore(":").isBlank()) "Not configured" else it.account.substringBefore(":")
        } ?: "Create a configuration from the menu", 12f).apply { setTextColor(Color.LTGRAY) })
        addCard(summary)

        val modes = card()
        modes.addView(label("NETWORK MODE", 11f).apply { setTextColor(Color.LTGRAY) })
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Global Mode", "Smart Auto Mode").forEach { mode ->
            val selected = selectedMode == mode
            val chip = TextView(this).apply {
                text = (if (selected) "✓  " else "") + mode
                gravity = Gravity.CENTER; textSize = 12f; setTextColor(Color.WHITE)
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setBackgroundColor(if (selected) Color.rgb(39, 91, 155) else Color.rgb(38, 34, 51))
                setOnClickListener { selectedMode = mode; prefs.edit().putString("mode", selectedMode).apply(); showHome() }
            }
            modeRow.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(5), dp(3), dp(2)) })
        }
        modes.addView(modeRow); addCard(modes)
        c.addView(label("SSH  •  DNSTT  •  TUN PROTECTION", 10f).apply { gravity = Gravity.CENTER; setTextColor(Color.GRAY) })
    }

    private fun openHomeMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("Config Manager").setOnMenuItemClickListener { showConfigManager(); true }
        popup.menu.add("Speed / DNS / IP").setOnMenuItemClickListener { showTools(); true }
        popup.menu.add("Split Tunneling").setOnMenuItemClickListener { showSplitTunneling(); true }
        popup.menu.add("Connection Logs").setOnMenuItemClickListener { showLogs(); true }
        popup.menu.add("Settings").setOnMenuItemClickListener { showSettings(); true }
        popup.menu.add("Exit").setOnMenuItemClickListener {
            AlertDialog.Builder(this).setTitle("Exit Anu TUNNEL?")
                .setMessage("The VPN connection will remain active unless you disconnect it.")
                .setNegativeButton("Cancel", null).setPositiveButton("Exit") { _, _ -> finish() }.show()
            true
        }
        popup.show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!isHomeScreen) { showHome(); return }
        AlertDialog.Builder(this).setTitle("Exit Anu TUNNEL?")
            .setMessage("Do you want to close the app?")
            .setNegativeButton("Stay", null).setPositiveButton("Exit") { _, _ -> finish() }.show()
    }

    private fun connectNotice() {
        val selected = current ?: store.all().firstOrNull()
        if (selected == null) { toast("Create a configuration first"); return }
        if (selectedMode != "Smart Auto Mode" || !prefs.getBoolean("smart_dns", true)) {
            requestVpnFor(selected)
            return
        }
        val profiles = store.all().filter { it.udpDns.isNotBlank() }
        if (profiles.isEmpty()) { toast("No profiles with DNS resolvers"); return }
        toast("Smart Auto: testing ${profiles.size} DNS resolver(s)…")
        thread {
            val tested = profiles.mapNotNull { profile ->
                val latency = probeDnsLatency(profile.udpDns)
                if (latency != null) profile to latency else null
            }.sortedBy { it.second }
            runOnUiThread {
                if (tested.isEmpty()) {
                    toast("Smart Auto: no configured DNS resolver responded. Check DNS IP/port and network.")
                    addLog("Smart Auto: all DNS resolver probes failed")
                } else {
                    val best = tested.first().first
                    current = best
                    store.select(best.id)
                    addLog("Smart Auto selected ${best.name} (${tested.first().second} ms DNS response); ${tested.size}/${profiles.size} resolver(s) responded")
                    toast("Smart Auto selected ${best.name} · ${tested.first().second} ms")
                    requestVpnFor(best)
                }
            }
        }
    }

    /** Sends a real DNS A query and validates the transaction ID in the UDP response. */
    private fun probeDnsLatency(endpoint: String): Long? {
        return try {
            val parts = endpoint.split(":", limit = 2)
            if (parts.size != 2) return null
            val host = parts[0]
            val port = parts[1].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val id = SecureRandom().nextInt(65536)
            val queryBytes = ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { out ->
                    out.writeShort(id); out.writeShort(0x0100); out.writeShort(1)
                    out.writeShort(0); out.writeShort(0); out.writeShort(0)
                    "example.com".split(".").forEach { label ->
                        val raw = label.toByteArray(Charsets.US_ASCII)
                        out.writeByte(raw.size); out.write(raw)
                    }
                    out.writeByte(0); out.writeShort(1); out.writeShort(1)
                }
            }.toByteArray()
            DatagramSocket().use { socket ->
                socket.soTimeout = 1800
                val address = InetAddress.getByName(host)
                val sent = System.nanoTime()
                socket.send(DatagramPacket(queryBytes, queryBytes.size, address, port))
                val response = ByteArray(1500)
                val packet = DatagramPacket(response, response.size)
                socket.receive(packet)
                if (packet.length < 12) return null
                val responseId = ((response[0].toInt() and 0xff) shl 8) or (response[1].toInt() and 0xff)
                if (responseId != id) return null
                ((System.nanoTime() - sent) / 1_000_000L).coerceAtLeast(1L)
            }
        } catch (_: Exception) { null }
    }

    private fun requestVpnFor(config: TunnelConfig) {
        if (config.connectionType != "SSH Through DNSTT") {
            toast("${config.connectionType} Core is not integrated yet. Select SSH Through DNSTT to connect.")
            return
        }
        if (!config.udpgwPortConfigured || config.udpgwPort !in 1..65535) {
            toast("Ask your SSH provider for the exact BadVPN UDPGW TCP port, then save it in this profile.")
            showEditor(config)
            return
        }
        val credentials = config.account.split(":", limit = 2)
        if (credentials.size != 2 || credentials[0].isBlank() || credentials[1].isBlank()) {
            toast("Account format must be SSH_USERNAME:SSH_PASSWORD")
            return
        }
        current = config
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) startActivityForResult(permissionIntent, reqVpn)
        else startVpnService(config, credentials[0], credentials[1])
    }

    private fun startVpnService(config: TunnelConfig, username: String, password: String) {
        val intent = Intent(this, AnuVpnService::class.java).setAction(AnuVpnService.ACTION_CONNECT)
            .putExtra("dns", config.udpDns)
            .putExtra("domain", config.serverName)
            .putExtra("public_key", config.publicKey)
            .putExtra("ssh_username", username)
            .putExtra("ssh_password", password)
            .putExtra("network_mode", selectedMode)
            .putExtra("udpgw_port", config.udpgwPort)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        toast("Anu TUNNEL connection started")
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(AnuVpnService.ACTION_STATE).apply { addAction(AnuVpnService.ACTION_STATS) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(tunnelStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(tunnelStateReceiver, filter)
        val statePrefs = getSharedPreferences("anu_tunnel_state", MODE_PRIVATE)
        statusLabel?.text = "● ${statePrefs.getString("state", "DISCONNECTED")}\n${statePrefs.getString("detail", "Ready")}"
    }

    override fun onPause() {
        try { unregisterReceiver(tunnelStateReceiver) } catch (_: Exception) {}
        super.onPause()
    }

    private fun showConfigManager() {
        val c = base("Config Manager")
        val management = card()
        management.addView(label("PROFILE MANAGEMENT", 12f))
        actionGrid(management, listOf(
            "＋ New Config" to {
                current = TunnelConfig(name = "New Config")
                showEditor(current!!)
            },
            "Import JSON" to { pickImport() },
            "Export All" to { exportText(store.exportJson(), "anu-tunnel-configs.json") },
            "Reset All" to {
                AlertDialog.Builder(this).setTitle("Reset all configs?")
                    .setMessage("All saved configurations will be removed and the starter profile will be restored.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reset") { _, _ ->
                        store.reset()
                        val starter = TunnelConfig()
                        store.upsert(starter); store.select(starter.id); current = starter
                        addLog("All configurations reset")
                        showConfigManager()
                    }.show()
            }
        ))
        addCard(management)

        store.all().forEach { config ->
            val item = card()
            val active = config.id == current?.id
            item.addView(label(if (active) "✓  ${config.name}  •  ACTIVE" else config.name, 17f).apply {
                setTextColor(if (active) Color.rgb(205, 145, 255) else white)
            })
            val safeAccount = config.account.substringBefore(":").ifBlank { "Not configured" }
            item.addView(label("${config.connectionType}\nSSH: $safeAccount\nDNS: ${config.udpDns}\nSNI: ${config.serverName}\nUDP Gateway: ${config.udpgwPort}", 12f))
            actionGrid(item, listOf(
                "Use Profile" to {
                    current = config; store.select(config.id)
                    addLog("Selected config: ${config.name}")
                    toast("Selected: ${config.name}"); showHome()
                },
                "Edit" to { showEditor(config) },
                "Export" to { exportText(store.exportJson(listOf(config)), safeFileName(config.name) + ".json") },
                "Delete" to {
                    AlertDialog.Builder(this).setMessage("Delete ${config.name}?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            store.delete(config.id)
                            if (current?.id == config.id) current = store.all().firstOrNull()
                            if (store.all().isEmpty()) {
                                val starter = TunnelConfig(); store.upsert(starter); current = starter
                            }
                            current?.let { store.select(it.id) }
                            addLog("Deleted config: ${config.name}")
                            showConfigManager()
                        }.show()
                }
            ))
            addCard(item)
        }
        c.addView(button("←  HOME") { showHome() })
    }

    private fun showEditor(config: TunnelConfig) {
        val c = base("Config Input")
        val fields = mutableMapOf<String, EditText>()
        fun add(key: String, title: String, value: String, hint: String = "") {
            val pair = field(title, value, hint)
            c.addView(pair.first)
            fields[key] = pair.second
        }
        add("name", "Config Name", config.name)
        c.addView(label("Tunnel Type", 13f))
        val tunnelTypes = listOf("SSH", "SSH Through DNSTT", "VMess", "VLess", "Trojan", "Shadowsocks", "V2Ray")
        val typeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, tunnelTypes)
            setSelection(tunnelTypes.indexOf(config.connectionType).takeIf { it >= 0 } ?: tunnelTypes.indexOf("SSH Through DNSTT"))
        }
        c.addView(typeSpinner, LinearLayout.LayoutParams(-1, dp(48)))
        c.addView(label("SSH Through DNSTT is the currently implemented tunnel core. Other protocol choices are shown for profile compatibility but cannot connect until their matching core is integrated.", 11f).apply { setTextColor(Color.LTGRAY) })
        add("account", "SSH Account (Username:Password)", config.account)
        add("dns", "UDP DNS (IP:Port)", config.udpDns, "8.8.8.8:53")
        add("udpgw", "Provider UDPGW TCP Port", if (config.udpgwPortConfigured) config.udpgwPort.toString() else "", "Ask provider; no universal default")
        c.addView(label("This is the port on the SSH server running badvpn-udpgw. 7300 is not assumed. Confirm it with your provider.", 12f))
        add("sni", "Server Name (SNI / Tunnel domain)", config.serverName)
        add("key", "Public Key", config.publicKey)
        add("payload", "Payload (Optional)", config.payload)
        val dnsOnly = CheckBox(this).apply {
            text = "Use DNS Tunneling Only (DNSTT)"
            setTextColor(white)
            isChecked = config.dnsOnly
        }
        c.addView(dnsOnly)
        val makeDefault = CheckBox(this).apply {
            text = "Save as Default Config"
            setTextColor(white)
            isChecked = config.isDefault
        }
        c.addView(makeDefault)

        c.addView(button("Save Config") {
            val name = fields["name"]!!.text.toString().trim()
            val account = fields["account"]!!.text.toString().trim()
            val dns = fields["dns"]!!.text.toString().trim()
            val udpgwPort = fields["udpgw"]!!.text.toString().trim().toIntOrNull()
            val sni = fields["sni"]!!.text.toString().trim()
            val key = fields["key"]!!.text.toString().trim()
            val dnsParts = dns.split(":")
            val port = dnsParts.getOrNull(1)?.toIntOrNull()
            val ipValid = dnsParts.size == 2 && dnsParts[0].matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")) &&
                dnsParts[0].split(".").all { part -> part.toIntOrNull()?.let { value -> value in 0..255 } == true }
            if (name.isBlank() || account.isBlank() || dns.isBlank() || sni.isBlank() || key.isBlank()) {
                toast("Required fields are missing")
                return@button
            }
            if (!ipValid || port == null || port !in 1..65535) {
                toast("DNS must be a valid IPv4:Port, e.g. 8.8.8.8:53")
                return@button
            }
            if (udpgwPort == null || udpgwPort !in 1..65535) {
                toast("Enter the exact UDPGW TCP port supplied by your SSH provider. There is no universal default.")
                return@button
            }
            if (!key.matches(Regex("^[0-9a-fA-F]{64}$"))) {
                toast("DNSTT public key must be 64 hexadecimal characters")
                return@button
            }
            config.name = name
            config.connectionType = typeSpinner.selectedItem?.toString() ?: "SSH Through DNSTT"
            config.account = account
            config.udpDns = dns
            config.udpgwPort = udpgwPort
            config.udpgwPortConfigured = true
            config.serverName = sni
            config.publicKey = key
            config.payload = fields["payload"]!!.text.toString()
            config.dnsOnly = dnsOnly.isChecked
            config.isDefault = makeDefault.isChecked
            if (config.isDefault) {
                val others = store.all().onEach { other ->
                    if (other.id != config.id) other.isDefault = false
                }
                store.save(others)
            }
            store.upsert(config)
            current = config
            store.select(config.id)
            addLog("Saved config: ${config.name}")
            toast("Configuration saved")
            showConfigManager()
        })
        c.addView(button("Reset Fields") { showEditor(TunnelConfig(id = config.id)) })
        c.addView(button("Cancel") { showConfigManager() })
    }

    private fun pickImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json")
            .addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(intent, reqImport)
    }

    private fun exportText(text: String, name: String) {
        pendingExport = text
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, name)
        startActivityForResult(intent, reqExport)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqVpn) {
            if (resultCode == RESULT_OK) {
                try {
                    val config = current ?: store.all().firstOrNull() ?: error("No configuration selected")
                    val credentials = config.account.split(":", limit = 2)
                    if (credentials.size != 2 || credentials.any { it.isBlank() }) error("Account format must be SSH_USERNAME:SSH_PASSWORD")
                    startVpnService(config, credentials[0], credentials[1])
                } catch (error: Exception) { toast(error.message ?: "VPN permission failed") }
            } else toast("VPN permission was not granted")
            return
        }
        if (resultCode != RESULT_OK || data?.data == null) return
        try {
            when (requestCode) {
                reqImport -> {
                    val raw = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Cannot read selected file")
                    val count = store.importJson(raw)
                    current = store.all().firstOrNull { it.id == store.selectedId() } ?: store.all().firstOrNull()
                    addLog("Imported $count config(s)")
                    toast("Imported $count config(s)")
                    showConfigManager()
                }
                reqExport -> {
                    contentResolver.openOutputStream(data.data!!)?.bufferedWriter()?.use { it.write(pendingExport) }
                        ?: throw IllegalStateException("Cannot write selected file")
                    addLog("Configuration export completed")
                    toast("Export completed")
                }
            }
        } catch (error: Exception) {
            toast("File operation failed: ${error.message ?: "Unknown error"}")
        }
    }

    private fun showTools() {
        val c = base("Tools")
        val result = label("Ready", 16f)
        c.addView(result)
        c.addView(button("DNS Lookup Test") {
            result.text = "Testing DNS lookup..."
            thread {
                val start = System.nanoTime()
                val message = try {
                    InetAddress.getByName("dns.google")
                    "DNS lookup completed in ${(System.nanoTime() - start) / 1_000_000} ms (not an internet speed test)"
                } catch (error: Exception) { "DNS lookup failed: ${error.message}" }
                runOnUiThread { result.text = message; addLog(message) }
            }
        })
        c.addView(button("Network Info") {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = manager.activeNetwork
            val caps = manager.getNetworkCapabilities(network)
            result.text = if (caps == null) "No active network" else buildString {
                append("Network connected\n")
                append("Wi-Fi: ${caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}\n")
                append("Cellular: ${caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}\n")
                append("Ethernet: ${caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)}\n")
                append("Validated internet: ${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
            }
        })
        c.addView(button("Real Download Speed Test (10 MB)") {
            AlertDialog.Builder(this).setTitle("Use about 10 MB of data?")
                .setMessage("This test downloads a 10 MB test file from Cloudflare. Your carrier may charge data fees.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Start") { _, _ -> runSpeedTest(result) }.show()
        })
        c.addView(button("Public IP Checker") {
            result.text = "Checking public IP..."
            thread {
                val message = try {
                    val proxy = if (AnuVpnService.isRunning.get()) java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 1080)) else java.net.Proxy.NO_PROXY
                    val connection = (URL("https://api.ipify.org").openConnection(proxy) as HttpURLConnection).apply {
                        connectTimeout = 12000; readTimeout = 12000; requestMethod = "GET"
                    }
                    val ip = connection.inputStream.bufferedReader().use { it.readText().trim() }
                    connection.disconnect()
                    "Public IP: $ip"
                } catch (error: Exception) { "IP lookup failed: ${error.message}" }
                runOnUiThread { result.text = message; addLog(message) }
            }
        })
        c.addView(button("← Home") { showHome() })
    }

    private fun runSpeedTest(output: TextView) {
        output.text = "Downloading test data..."
        thread {
            val started = System.nanoTime()
            val message = try {
                val proxy = if (AnuVpnService.isRunning.get()) java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 1080)) else java.net.Proxy.NO_PROXY
                val connection = (URL("https://speed.cloudflare.com/__down?bytes=10000000").openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 45000; requestMethod = "GET"
                }
                var bytes = 0L
                val buffer = ByteArray(32 * 1024)
                connection.inputStream.use { stream ->
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        bytes += count
                    }
                }
                connection.disconnect()
                val seconds = ((System.nanoTime() - started) / 1_000_000_000.0).coerceAtLeast(0.001)
                val mbps = (bytes * 8.0 / seconds) / 1_000_000.0
                "Measured download: ${String.format(Locale.US, "%.2f", mbps)} Mbps\nData: ${bytes / 1_000_000.0} MB | Time: ${String.format(Locale.US, "%.2f", seconds)} s"
            } catch (error: Exception) { "Speed test failed: ${error.message}" }
            runOnUiThread { output.text = message; addLog(message) }
        }
    }

    private fun showSplitTunneling() {
        val c = base("Split Tunneling")
        c.addView(label("Select apps to bypass the VPN. Changes apply the next time the VPN connects.", 13f))
        val selected = prefs.getStringSet("split_excluded", emptySet<String>())?.toMutableSet() ?: mutableSetOf()
        val apps = packageManager.getInstalledApplications(0).sortedBy { packageManager.getApplicationLabel(it).toString().lowercase(Locale.ROOT) }
        apps.forEach { app ->
            val name = packageManager.getApplicationLabel(app).toString()
            val check = CheckBox(this).apply {
                text = name
                setTextColor(white)
                isChecked = app.packageName in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                    prefs.edit().putStringSet("split_excluded", selected.toSet()).apply()
                }
            }
            c.addView(check)
        }
        c.addView(button("← Home") { showHome() })
    }

    private fun showLogs() {
        val c = base("Connection Logs")
        val entries = logs.getString("entries", "") ?: ""
        c.addView(label(if (entries.isBlank()) "No logs yet" else entries, 12f))
        c.addView(button("Clear Logs") {
            logs.edit().remove("entries").apply()
            addLog("Logs cleared")
            showLogs()
        })
        c.addView(button("← Home") { showHome() })
    }

    private fun showSettings() {
        val c = base("Settings")
        c.addView(label("Anu TUNNEL v1.8.0\nDNSTT → SSH → SOCKS5 → TUN packet forwarding\nSaved SSH credentials are encrypted with Android Keystore. Exported JSON contains credentials in readable form; keep exports private. SSH host keys are pinned on first use."))
        addSettingSwitch(c, "Auto Reconnect", "auto_reconnect", false)
        addSettingSwitch(c, "Smart DNS Resolver Selection", "smart_dns", true)
        addSettingSwitch(c, "Keep CPU Awake While Connected", "keep_alive", true)
        addSettingSwitch(c, "SSH Compression (zlib)", "ssh_compression", false)
        addSettingSwitch(c, "Show Connection Log", "show_logs", true)
        addSettingSwitch(c, "Dark Theme", "dark_theme", true)

        val pingField = field("HTTP Ping URL", prefs.getString("http_ping_url", "https://www.google.com/generate_204") ?: "https://www.google.com/generate_204", "https://example.com/")
        c.addView(pingField.first)
        val delayField = field("Auto Reconnect Delay (seconds)", prefs.getInt("reconnect_delay_seconds", 5).toString(), "2–120")
        c.addView(delayField.first)
        val mtuField = field("VPN MTU", prefs.getInt("vpn_mtu", 1400).toString(), "576–9000")
        c.addView(mtuField.first)
        c.addView(button("Save Advanced Settings") {
            val pingUrl = pingField.second.text.toString().trim()
            val delay = delayField.second.text.toString().trim().toIntOrNull()
            val mtu = mtuField.second.text.toString().trim().toIntOrNull()
            val parsed = try { URL(pingUrl) } catch (_: Exception) { null }
            if (parsed == null || (parsed.protocol != "http" && parsed.protocol != "https")) {
                toast("HTTP Ping URL must be a valid http:// or https:// URL")
                return@button
            }
            if (delay == null || delay !in 2..120) {
                toast("Reconnect delay must be between 2 and 120 seconds")
                return@button
            }
            if (mtu == null || mtu !in 576..9000) {
                toast("VPN MTU must be between 576 and 9000")
                return@button
            }
            prefs.edit().putString("http_ping_url", pingUrl)
                .putInt("reconnect_delay_seconds", delay)
                .putInt("vpn_mtu", mtu).apply()
            toast("Advanced settings saved. Reconnect the VPN to apply MTU changes.")
        })
        c.addView(button("Clear Logs") {
            logs.edit().remove("entries").apply()
            toast("Logs cleared")
        })
        c.addView(button("About Anu TUNNEL") {
            AlertDialog.Builder(this).setTitle("Anu TUNNEL v1.8.0")
                .setMessage("SSH • DNSTT\nThis build integrates the DNSTT Go client, SSH-over-DNSTT authentication, and hev tun2socks packet forwarding. A successful connection still requires a valid provider account and a DNSTT server configured to forward to its SSH service.")
                .setPositiveButton("OK", null).show()
        })
        c.addView(button("← Home") { showHome() })
    }

    private fun addSettingSwitch(container: LinearLayout, title: String, key: String, default: Boolean) {
        val toggle = Switch(this).apply {
            text = title
            setTextColor(white)
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
        container.addView(toggle)
    }

    private fun addLog(message: String) {
        if (!prefs.getBoolean("show_logs", true)) return
        val old = logs.getString("entries", "") ?: ""
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date())}] $message"
        val updated = (listOf(line) + old.lines().filter { it.isNotBlank() }).take(200).joinToString("\n")
        logs.edit().putString("entries", updated).apply()
    }

    private fun formatRate(bytesPerSecond: Long): String = if (bytesPerSecond >= 1024 * 1024)
        String.format(Locale.US, "%.2f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    else String.format(Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)

    private fun formatBytes(bytes: Long): String = if (bytes >= 1024L * 1024L)
        String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0)) else "${bytes / 1024} KB"

    private fun safeFileName(value: String) = value.replace(Regex("[^A-Za-z0-9_-]"), "_").take(48).ifBlank { "anu-config" }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
