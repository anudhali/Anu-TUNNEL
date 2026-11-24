package com.anutunnel.app

import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.Proxy
import java.net.URL
import java.net.HttpURLConnection
import java.net.SocketException
import java.util.Properties
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** SSH session over the local TCP stream exposed by the DNSTT server, plus a SOCKS5 CONNECT listener. */
class SshSocksProxy {
    companion object { private const val TAG = "AnuSshProxy" }

    private val running = AtomicBoolean(false)
    private val uploadedBytes = AtomicLong(0)
    private val downloadedBytes = AtomicLong(0)
    private val workers: ExecutorService = Executors.newFixedThreadPool(32)
    private var session: Session? = null
    private var server: ServerSocket? = null

    fun connect(username: String, password: String, localDnsttPort: Int = 7000, socksPort: Int = 1080, hostKeyId: String = "default", pinPrefs: android.content.SharedPreferences? = null, compression: Boolean = false) {
        check(!running.get()) { "SSH proxy is already running" }
        require(username.isNotBlank() && password.isNotBlank()) { "SSH username/password is missing" }
        val jsch = JSch()
        val ssh = jsch.getSession(username, "127.0.0.1", localDnsttPort)
        ssh.setPassword(password)
        val cfg = Properties().apply {
            setProperty("StrictHostKeyChecking", "no")
            setProperty("PreferredAuthentications", "password,keyboard-interactive")
            val method = if (compression) "zlib@openssh.com,zlib,none" else "none"
            setProperty("compression.s2c", method)
            setProperty("compression.c2s", method)
            setProperty("compression_level", "6")
        }
        ssh.setConfig(cfg)
        ssh.timeout = 30_000
        ssh.setServerAliveInterval(15_000)
        ssh.setServerAliveCountMax(3)
        ssh.connect(30_000)
        check(ssh.isConnected) { "SSH authentication failed" }
        // Trust-on-first-use: pin the SSH server key per DNSTT profile and reject later changes.
        val presentedKey = ssh.hostKey?.key ?: run { ssh.disconnect(); error("SSH server did not present a host key") }
        val pinName = "ssh_hostkey_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(hostKeyId.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        if (pinPrefs != null) {
            val pinned = pinPrefs.getString(pinName, null)
            if (pinned == null) pinPrefs.edit().putString(pinName, presentedKey).apply()
            else if (!java.security.MessageDigest.isEqual(pinned.toByteArray(Charsets.UTF_8), presentedKey.toByteArray(Charsets.UTF_8))) {
                ssh.disconnect()
                error("SSH host key changed for this profile; refusing connection to protect against interception")
            }
        }

        val listener = ServerSocket(socksPort, 64, InetAddress.getByName("127.0.0.1"))
        session = ssh
        server = listener
        running.set(true)
        workers.execute {
            while (running.get()) {
                try {
                    val client = listener.accept()
                    workers.execute { handleClient(client) }
                } catch (e: SocketException) {
                    if (running.get()) Log.e(TAG, "SOCKS listener stopped unexpectedly", e)
                    break
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "SOCKS accept error", e)
                }
            }
        }
        Log.i(TAG, "SSH authenticated through DNSTT; SOCKS5 listening on 127.0.0.1:$socksPort")
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = BufferedOutputStream(client.getOutputStream())
            try {
                val version = input.readUnsignedByte()
                val methodCount = input.readUnsignedByte()
                if (version != 5 || methodCount !in 1..32) return
                val methods = ByteArray(methodCount)
                input.readFully(methods)
                if (methods.none { it == 0.toByte() }) {
                    output.write(byteArrayOf(5, 0xFF.toByte())); output.flush(); return
                }
                output.write(byteArrayOf(5, 0)); output.flush()

                val requestVersion = input.readUnsignedByte()
                val command = input.readUnsignedByte()
                input.readUnsignedByte() // reserved
                val addressType = input.readUnsignedByte()
                if (requestVersion != 5 || command != 1) {
                    sendReply(output, 7); return
                }
                val host = when (addressType) {
                    1 -> readExact(input, 4)
                        .joinToString(".") { (it.toInt() and 0xff).toString() }
                    3 -> {
                        val n = input.readUnsignedByte()
                        if (n == 0) throw EOFException()
                        String(readExact(input, n), Charsets.UTF_8)
                    }
                    4 -> InetAddress.getByAddress(readExact(input, 16)).hostAddress
                    else -> { sendReply(output, 8); return }
                }
                val port = input.readUnsignedShort()
                if (port == 0) { sendReply(output, 1); return }

                val ssh = session?.takeIf { it.isConnected } ?: run { sendReply(output, 1); return }
                val channel = ssh.openChannel("direct-tcpip") as ChannelDirectTCPIP
                channel.setHost(host)
                channel.setPort(port)
                channel.setOrgIPAddress("127.0.0.1")
                channel.setOrgPort(0)
                channel.connect(20_000)
                client.soTimeout = 0 // Keep established TCP streams alive during long idle periods.
                sendReply(output, 0)
                val remoteIn = channel.inputStream
                val remoteOut = channel.outputStream
                val toRemote = Thread {
                    try { copyCounted(client.getInputStream(), remoteOut, uploadedBytes); remoteOut.flush() }
                    catch (_: Exception) { }
                    finally { try { channel.disconnect() } catch (_: Exception) {} }
                }.apply { name = "AnuSocks-Upstream"; isDaemon = true; start() }
                try {
                    copyCounted(remoteIn, output, downloadedBytes)
                    output.flush()
                } catch (_: Exception) { }
                toRemote.join(2_000)
                try { channel.disconnect() } catch (_: Exception) {}
            } catch (e: Exception) {
                Log.w(TAG, "SOCKS client closed: ${e.message}")
                try { sendReply(output, 1) } catch (_: Exception) {}
            }
        }
    }

    private fun copyCounted(input: java.io.InputStream, output: java.io.OutputStream, counter: AtomicLong) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            counter.addAndGet(count.toLong())
        }
        output.flush()
    }

    fun trafficTotals(): Pair<Long, Long> = uploadedBytes.get() to downloadedBytes.get()

    /** HTTP response latency measured through the local SOCKS5 -> SSH -> DNSTT chain. */
    fun pingLatency(url: String, timeoutMs: Int = 4_000): Long? {
        var connection: HttpURLConnection? = null
        return try {
            val parsed = URL(url)
            require(parsed.protocol == "http" || parsed.protocol == "https") { "Ping URL must use HTTP or HTTPS" }
            val started = System.nanoTime()
            val active = (parsed.openConnection(
                Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 1080))
            ) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AnuTUNNEL-HTTP-Ping/1.0")
                setRequestProperty("Range", "bytes=0-0")
                useCaches = false
            }
            connection = active
            val code = active.responseCode
            if (code !in 200..399) return null
            try { active.inputStream.use { it.read() } } catch (_: Exception) { }
            ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(1L)
        } catch (e: Exception) {
            Log.d(TAG, "HTTP ping failed for $url: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readExact(input: DataInputStream, size: Int): ByteArray = ByteArray(size).also { input.readFully(it) }

    private fun sendReply(out: BufferedOutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    /** Check real TCP CONNECT forwarding using several independent numeric endpoints. */
    fun verifyTcpForwarding(timeoutMs: Int = 4_000): Boolean {
        val proxy = Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 1080))
        val targets = listOf("1.1.1.1" to 443, "8.8.8.8" to 443, "9.9.9.9" to 443)
        for ((host, port) in targets) {
            try {
                Socket(proxy).use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                    if (socket.isConnected) return true
                }
            } catch (e: Exception) {
                Log.d(TAG, "TCP forwarding probe $host:$port failed: ${e.message}")
            }
        }
        return false
    }

    /** Check whether the SSH host exposes its provider-side BadVPN UDPGW TCP service. */
    fun verifyUdpGatewayPort(port: Int, timeoutMs: Int = 4_000): Boolean {
        if (port !in 1..65535) return false
        return try {
            Socket(Proxy(Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", 1080))).use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
                socket.isConnected
            }
        } catch (e: Exception) {
            Log.w(TAG, "Remote UDPGW 127.0.0.1:$port is not reachable: ${e.message}")
            false
        }
    }

    /** Kept for compatibility with older callers; no longer depends on api.ipify.org. */
    fun verifyProxy(timeoutMs: Int = 4_000): Boolean = verifyTcpForwarding(timeoutMs)

    fun isConnected(): Boolean = running.get() && session?.isConnected == true

    fun disconnect() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        workers.shutdownNow()
    }
}
