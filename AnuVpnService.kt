package com.anutunnel.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import hev.htproxy.TProxyService
import go.dnsttbridge.Client
import go.dnsttbridge.Dnsttbridge
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Full path: Android TUN -> hev tun2socks -> local SSH SOCKS5 -> SSH-over-DNSTT -> provider gateway. */
class AnuVpnService : VpnService() {
    companion object {
        const val ACTION_CONNECT = "com.anutunnel.app.CONNECT"
        const val ACTION_DISCONNECT = "com.anutunnel.app.DISCONNECT"
        const val ACTION_STATE = "com.anutunnel.app.TUNNEL_STATE"
        const val ACTION_STATS = "com.anutunnel.app.TUNNEL_STATS"
        private const val CHANNEL_ID = "anu_tunnel_vpn"
        private const val NOTIFICATION_ID = 4101
        private const val TAG = "AnuVpnService"
        val isRunning = AtomicBoolean(false)
    }

    private var tun: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var dnstt: Client? = null
    private var sshProxy: SshSocksProxy? = null
    private var nativeThread: Thread? = null
    private val stopping = AtomicBoolean(false)
    private val networkWasLost = AtomicBoolean(false)
    @Volatile private var lastConnectIntent: Intent? = null
    private val retryQueued = AtomicBoolean(false)
    private val retryExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var statsFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var pingFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var udpStatusFuture: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var lastUdpGatewayState = Int.MIN_VALUE
    private val statsExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var lastPingMs: Long = -1L
    private val connectQueued = AtomicBoolean(false)
    private val commandExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AnuTunnel-ServiceCommands").apply { isDaemon = true }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = intent ?: return START_NOT_STICKY
        when (command.action) {
            ACTION_CONNECT -> {
                if (isRunning.get() || !connectQueued.compareAndSet(false, true)) return START_NOT_STICKY
                stopping.set(false)
                lastConnectIntent = Intent(command)
                registerNetworkCallback()
                createChannel()
                startForeground(NOTIFICATION_ID, notification("Connecting"))
                commandExecutor.execute {
                    try { connect(command) } finally { connectQueued.set(false) }
                }
            }
            ACTION_DISCONNECT -> {
                // Signal a currently-running handshake immediately; cleanup is serialized behind it.
                stopping.set(true)
                lastConnectIntent = null
                networkWasLost.set(false)
                retryQueued.set(false)
                commandExecutor.execute { disconnect("Disconnected by user") }
            }
            else -> return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    private fun ensureNotStopping() {
        check(!stopping.get()) { "Connection cancelled" }
    }

    private fun connect(intent: Intent) {
        if (isRunning.get()) return
        try {
            ensureNotStopping()
            publishState("CONNECTING", "Preparing VPN")

            val dns = intent.getStringExtra("dns") ?: error("DNS resolver missing")
            val domain = intent.getStringExtra("domain") ?: error("DNSTT domain missing")
            val key = intent.getStringExtra("public_key") ?: error("DNSTT public key missing")
            val username = intent.getStringExtra("ssh_username") ?: error("SSH username missing")
            val password = intent.getStringExtra("ssh_password") ?: error("SSH password missing")
            val networkMode = intent.getStringExtra("network_mode") ?: "Global Mode"
            val udpgwPort = intent.getIntExtra("udpgw_port", 0)
            require(udpgwPort in 1..65535) { "UDP Gateway Port must be between 1 and 65535" }
            val settings = getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE)
            val mtu = settings.getInt("vpn_mtu", 1400).coerceIn(576, 9000)
            if (settings.getBoolean("keep_alive", true)) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AnuTUNNEL::VPN").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
            require(key.matches(Regex("^[0-9a-fA-F]{64}$"))) { "DNSTT public key must be 64 hex characters" }
            require(domain.isNotBlank()) { "DNSTT domain is empty" }
            require(dns.matches(Regex("^(\\d{1,3}\\.){3}\\d{1,3}:\\d{1,5}$"))) { "DNS must be IPv4:port" }

            val builder = Builder()
                .setSession("Anu TUNNEL")
                .setMtu(mtu)
                .addAddress("10.111.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.111.0.2")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.addDisallowedApplication(packageName)
                val excluded = getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE)
                    .getStringSet("split_excluded", emptySet<String>()) ?: emptySet()
                excluded.forEach { pkg -> try { builder.addDisallowedApplication(pkg) } catch (e: Exception) { Log.w(TAG, "Cannot exclude $pkg", e) } }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                builder.addAddress("fc00:1111:1111::2", 128)
                builder.addRoute("::", 0)
            }
            tun = builder.establish() ?: error("Android refused to establish VPN interface")
            ensureNotStopping()

            // The app is excluded from its own VPN, so the DNSTT UDP resolver socket cannot loop into TUN.
            val dnsHostPort = dns.split(":", limit = 2)
            val dnsEndpoint = "${dnsHostPort[0]}:${dnsHostPort[1]}"
            publishState("CONNECTING", if (networkMode == "Smart Auto Mode")
                "Smart Auto: using the resolver selected by the DNS latency probe"
            else "Global: connecting with the selected provider profile")
            dnstt = Dnsttbridge.newClient(dnsEndpoint, domain, key, "127.0.0.1:7000")
            dnstt!!.start()
            ensureNotStopping()
            check(dnstt!!.isRunning) { "DNSTT native client did not start" }

            publishState("CONNECTING", "Authenticating SSH account")
            val proxy = SshSocksProxy()
            proxy.connect(
                username, password, 7000, 1080, domain,
                getSharedPreferences("anu_tunnel_hostkeys", MODE_PRIVATE),
                getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE).getBoolean("ssh_compression", false)
            )
            sshProxy = proxy
            ensureNotStopping()
            check(proxy.isConnected()) { "SSH authentication failed" }
            check(proxy.verifyTcpForwarding()) {
                "SSH authenticated, but TCP direct-tcpip forwarding to public endpoints failed. Check provider forwarding/account."
            }
            val udpGatewayReachable = proxy.verifyUdpGatewayPort(udpgwPort)
            check(udpGatewayReachable) {
                "Provider UDPGW TCP port 127.0.0.1:$udpgwPort is not reachable through SSH. Ask your provider for the exact port and enable its compatible BadVPN UDPGW service."
            }
            ensureNotStopping()

            publishState("CONNECTING", "Starting BadVPN TUN engine and SSH UDP gateway relay")
            check(BadVpnTunnel.reset()) { "A previous BadVPN engine is still stopping" }
            val fd = tun!!.fd
            nativeThread = Thread {
                try {
                    val result = BadVpnTunnel.run(
                        fd, mtu, "127.0.0.1:1080", "127.0.0.1:$udpgwPort",
                        "10.111.0.2", "255.255.255.0", "fc00:1111:1111::2"
                    )
                    Log.i(TAG, "BadVPN tunnel worker returned: $result")
                    if (!stopping.get() && isRunning.get()) {
                        commandExecutor.execute { disconnect("BadVPN packet engine stopped unexpectedly (code $result)") }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "BadVPN native engine failed", e)
                    if (!stopping.get()) commandExecutor.execute { disconnect("BadVPN engine: ${e.message}") }
                }
            }.apply { name = "AnuTunnel-BadVPN-TUN2SOCKS"; isDaemon = true; start() }

            // Do not announce Connected until the BadVPN reactor is actually running.
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline && !stopping.get()) {
                if (BadVpnTunnel.isRunning()) break
                Thread.sleep(100)
            }
            ensureNotStopping()
            check(BadVpnTunnel.isRunning()) {
                "BadVPN TUN engine did not enter its event loop. Check Android TUN fd and native library."
            }
            Thread.sleep(1_000)
            check(BadVpnTunnel.isRunning()) { "BadVPN tun2socks exited during startup" }
            val udpDeadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < udpDeadline && !stopping.get() && BadVpnTunnel.udpGatewayState() == 0) Thread.sleep(100)
            ensureNotStopping()
            check(BadVpnTunnel.udpGatewayState() >= 1) { "Provider UDPGW did not establish its SOCKS TCP stream. Verify the exact port and compatible BadVPN service." }
            isRunning.set(true)
            startStatsBroadcast()
            publishState("CONNECTED", "SSH/DNSTT + BadVPN UDPGW client started; remote UDP replies still require provider verification")
            updateNotification("Connected · SSH/DNSTT")
        } catch (e: Throwable) {
            Log.e(TAG, "Tunnel startup failed", e)
            if (stopping.get()) publishState("DISCONNECTED", "Connection cancelled")
            else publishState("ERROR", e.message ?: "Tunnel startup failed")
            cleanup()
            val autoReconnect = getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE)
                .getBoolean("auto_reconnect", false)
            val retryIntent = lastConnectIntent
            if (!stopping.get() && autoReconnect && retryIntent != null) {
                val delay = getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE)
                    .getInt("reconnect_delay_seconds", 5).coerceIn(2, 120)
                publishState("RECONNECTING", "Connection failed; retrying in $delay seconds")
                scheduleReconnect(retryIntent, delay.toLong())
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startStatsBroadcast() {
        statsFuture?.cancel(false)
        pingFuture?.cancel(false)
        statsFuture = statsExecutor.scheduleAtFixedRate({
            val proxy = sshProxy ?: return@scheduleAtFixedRate
            val totals = proxy.trafficTotals()
            val intent = Intent(ACTION_STATS).setPackage(packageName)
                .putExtra("uploaded", totals.first)
                .putExtra("downloaded", totals.second)
                .putExtra("ping_ms", lastPingMs)
            sendBroadcast(intent)
        }, 0, 1, TimeUnit.SECONDS)
        pingFuture = statsExecutor.scheduleAtFixedRate({
            val url = getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE)
                .getString("http_ping_url", "https://www.google.com/generate_204")
                ?: "https://www.google.com/generate_204"
            val value = sshProxy?.pingLatency(url)
            lastPingMs = value ?: -1L
        }, 1, 5, TimeUnit.SECONDS)
        udpStatusFuture = statsExecutor.scheduleAtFixedRate({
            val state = try { BadVpnTunnel.udpGatewayState() } catch (_: Throwable) { -1 }
            if (state != lastUdpGatewayState && isRunning.get()) {
                lastUdpGatewayState = state
                val detail = when (state) {
                    2 -> "UDP relay verified: a valid UDPGW response was received"
                    1 -> "UDPGW SOCKS/TCP stream connected; waiting for a valid UDP reply"
                    -1 -> "UDPGW stream/protocol error; native client is retrying. Check provider compatibility"
                    else -> "UDP gateway reconnecting"
                }
                publishState("CONNECTED", detail)
            }
        }, 1, 1, TimeUnit.SECONDS)
    }

    private fun scheduleReconnect(intent: Intent, delaySeconds: Long) {
        if (!retryQueued.compareAndSet(false, true)) return
        retryExecutor.schedule({
            retryQueued.set(false)
            if (!stopping.get() && getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE)
                    .getBoolean("auto_reconnect", false)) {
                commandExecutor.execute { if (!stopping.get()) connect(Intent(intent)) }
            }
        }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                val enabled = getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE)
                    .getBoolean("auto_reconnect", false)
                if (!enabled || stopping.get() || lastConnectIntent == null) return
                if (networkWasLost.compareAndSet(false, true)) {
                    commandExecutor.execute {
                        if (!stopping.get() && networkWasLost.get()) {
                            publishState("RECONNECTING", "Network changed; waiting for internet")
                            cleanup()
                        }
                    }
                }
            }

            override fun onAvailable(network: Network) {
                if (!networkWasLost.compareAndSet(true, false)) return
                val intent = lastConnectIntent ?: return
                val delay = getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE)
                    .getInt("reconnect_delay_seconds", 5).coerceIn(2, 120)
                commandExecutor.execute {
                    if (!stopping.get() && getSharedPreferences("anu_tunnel_settings", MODE_PRIVATE)
                            .getBoolean("auto_reconnect", false)) {
                        publishState("RECONNECTING", "Network restored; reconnecting in $delay seconds")
                        scheduleReconnect(Intent(intent), delay.toLong())
                    }
                }
            }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Network callback registration failed", e)
        }
    }

    private fun disconnect(reason: String) {
        stopping.set(true)
        cleanup()
        publishState("DISCONNECTED", reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        isRunning.set(false)
        lastPingMs = -1L
        statsFuture?.cancel(false)
        pingFuture?.cancel(false)
        udpStatusFuture?.cancel(false)
        statsFuture = null
        pingFuture = null
        udpStatusFuture = null
        lastUdpGatewayState = Int.MIN_VALUE
        try { BadVpnTunnel.stop() } catch (e: Throwable) { Log.w(TAG, "BadVPN stop", e) }
        try { if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService() } catch (e: Throwable) { Log.w(TAG, "hev stop", e) }
        try { nativeThread?.join(5_000) } catch (_: Exception) {}
        if (nativeThread?.isAlive == true) Log.e(TAG, "BadVPN worker did not stop within 5 seconds; closing VPN descriptor as a last resort")
        nativeThread = null
        try { sshProxy?.disconnect() } catch (_: Exception) {}
        sshProxy = null
        try { dnstt?.stop() } catch (_: Exception) {}
        dnstt = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { Log.w(TAG, "WakeLock release", e) }
        wakeLock = null
    }

    private fun publishState(state: String, detail: String) {
        getSharedPreferences("anu_tunnel_state", MODE_PRIVATE).edit()
            .putString("state", state).putString("detail", detail).apply()
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("state", state).putExtra("detail", detail))
        if (state == "CONNECTED") updateNotification("Connected · SSH/DNSTT")
        Log.i(TAG, "$state: $detail")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Anu TUNNEL", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("Anu TUNNEL")
            .setContentText(text).setContentIntent(open).setOngoing(true).build()
        else @Suppress("DEPRECATION") Notification.Builder(this)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("Anu TUNNEL")
            .setContentText(text).setContentIntent(open).setOngoing(true).build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        stopping.set(true)
        commandExecutor.shutdownNow()
        retryExecutor.shutdownNow()
        statsExecutor.shutdownNow()
        try {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            networkCallback?.let { manager.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
        cleanup()
        super.onDestroy()
    }
}
