from pathlib import Path
p = Path("anu_tunnel/app/src/main/java/com/anutunnel/app/MainActivity.kt")
s = p.read_text()
if "private var isHomeScreen = false" not in s:
    s = s.replace('private var selectedMode = "Smart Auto Mode"', 'private var selectedMode = "Smart Auto Mode"\n    private var isHomeScreen = false')
s = s.replace("private fun base(title: String): LinearLayout {\n        root", "private fun base(title: String): LinearLayout {\n        isHomeScreen = false\n        root")
start = s.index("    private fun showHome() {")
end = s.index("    private fun connectNotice() {", start)
new_home = r'''    private fun showHome() {
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
        statusLabel = label("$state\n\${statePrefs.getString("detail", "Ready")}", 16f).apply {
            gravity = Gravity.CENTER
            setTextColor(if (connected) Color.rgb(90, 235, 160) else if (state == "ERROR") Color.RED else white)
        }
        statusCard.addView(statusLabel)
        statusCard.addView(label("Tap the circle to \${if (connected) "disconnect" else "connect"}", 11f).apply {
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
            "\${it.connectionType}  •  DNS \${it.udpDns}\nSNI \${it.serverName}\nSSH account: " +
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

'''
new_home = new_home.replace(r"\\${", "${")
s = s[:start] + new_home + s[end:]
s = s.replace("Anu TUNNEL v1.3.0", "Anu TUNNEL v1.5.0")
p.write_text(s)
g = Path("anu_tunnel/app/build.gradle")
gs = g.read_text().replace("versionCode 11", "versionCode 12").replace("versionName '1.4.0'", "versionName '1.5.0'")
g.write_text(gs)
print("Applied Anu TUNNEL v1.5.0 home UI, hamburger navigation, connect control, back confirmation and version bump")
