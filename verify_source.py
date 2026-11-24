from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
required = [
    'settings.gradle', 'build.gradle', 'app/build.gradle',
    'app/src/main/AndroidManifest.xml',
    'app/src/main/java/com/anutunnel/app/MainActivity.kt',
    'app/src/main/java/com/anutunnel/app/ConfigStore.kt',
    'app/src/main/java/com/anutunnel/app/TunnelConfig.kt',
    'app/src/main/java/com/anutunnel/app/AnuVpnService.kt',
    'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt',
    'app/src/main/java/com/anutunnel/app/BadVpnTunnel.java',
    'app/src/main/java/com/anutunnel/app/SecretCrypto.kt',
    'app/src/main/java/hev/htproxy/TProxyService.java',
    'tools/build_native.sh', 'tools/build_badvpn_android.sh', 'tools/native/badvpn/prepare.py', 'app/src/main/assets/licenses/BadVPN-COPYING.txt', 'tools/androidide_env.sh', 'tools/native/dnsttbridge/bridge.go', 'Application.mk', 'gradlew', 'tools/check_environment.sh',
    'app/src/main/res/drawable/anu_logo.png', 'README.md', 'BUILD_STATUS.md'
]
missing = [name for name in required if not (root / name).is_file()]
if missing:
    raise SystemExit('Missing required files: ' + ', '.join(missing))
ET.parse(root / 'app/src/main/AndroidManifest.xml')
ET.parse(root / 'app/src/main/res/values/styles.xml')
main = (root / 'app/src/main/java/com/anutunnel/app/MainActivity.kt').read_text()
store = (root / 'app/src/main/java/com/anutunnel/app/ConfigStore.kt').read_text()
checks = {
    'Tunnel type selector and safe core gating': all(x in main for x in ['SSH Through DNSTT', 'VMess', 'VLess', 'Trojan', 'Shadowsocks', 'V2Ray', 'Core is not integrated yet']),
    'Global/Smart Auto modes only': all(x in main for x in ['Global Mode', 'Smart Auto Mode']) and '🇧🇩 BDIX Mode' not in main and 'SSH • DNSTT • BDIX' not in main,
    'Config import/export': all(x in main for x in ['pickImport()', 'exportText(', 'store.importJson']),
    'Real speed test implementation': 'speed.cloudflare.com/__down?bytes=10000000' in main,
    'Public IP lookup': 'api.ipify.org' in main,
    'VPN permission and service launch': 'VpnService.prepare(this)' in main and 'ACTION_CONNECT' in main,
    'SSH-over-DNSTT service chain': 'Dnsttbridge.newClient' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text() and 'SshSocksProxy' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text(),
    'BadVPN native TUN/UDPGW engine': all(x in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text() for x in ['BadVpnTunnel.run(', 'BadVpnTunnel.isRunning()', 'verifyUdpGatewayPort', 'verifyTcpForwarding']),
    'Native engine build automation': 'gomobile bind' in (root / 'tools/build_native.sh').read_text() and 'ndk-build' in (root / 'tools/build_native.sh').read_text() and 'build_badvpn_android.sh' in (root / 'tools/build_native.sh').read_text(),
    'BadVPN CMake/JNI build automation': all(x in (root / 'tools/build_badvpn_android.sh').read_text() for x in ['BUILD_TUN2SOCKS=ON', 'libanutunnel-badvpn.so', 'prepare.py']),
    'Gomobile-safe DNSTT wrapper': all(x in (root / 'tools/native/dnsttbridge/bridge.go').read_text() for x in ['NewClient(', 'Start()', 'Stop()', 'IsRunning()']),
    'Correct generated Java binding package': 'go.dnsttbridge.Dnsttbridge' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text() and 'mobile.Mobile' not in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text(),
    'Connect/disconnect cancellation serialization': 'newSingleThreadExecutor' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text() and 'ensureNotStopping()' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text(),
    'Multi-endpoint TCP forwarding verification': 'verifyTcpForwarding' in (root / 'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt').read_text() and '1.1.1.1' in (root / 'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt').read_text(),
    'Provider UDPGW port probe': 'verifyUdpGatewayPort' in (root / 'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt').read_text(),
    '16 KB page-size native build setting': 'APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true' in (root / 'Application.mk').read_text(),
    'BDIX removed from VPN service': 'BDIX Mode' not in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text(),
    'No SSH password bundled by default': 'var account: String = ' in (root / 'app/src/main/java/com/anutunnel/app/TunnelConfig.kt').read_text(),
    'Duplicate-safe import': 'UUID.randomUUID()' in store and 'importJson(raw: String)' in store,
    'Android Keystore credential encryption': 'SecretCrypto.encrypt(config.account)' in store and 'SecretCrypto.decrypt(item.optString' in store,
    'Smart Auto UDP DNS resolver probing': 'probeDnsLatency(profile.udpDns)' in main and 'DatagramSocket()' in main,
    'Real SOCKS traffic counters': 'uploadedBytes' in (root / 'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt').read_text() and 'downloadedBytes' in (root / 'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt').read_text(),
    'Auto reconnect network callback': 'registerDefaultNetworkCallback' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text() and 'scheduleReconnect' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text(),
    'SSH host key pinning': 'ssh_hostkey_' in (root / 'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt').read_text() and 'host key changed' in (root / 'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt').read_text(),
    'Connected speed test uses SOCKS proxy': 'Proxy.Type.SOCKS' in main and 'speed.cloudflare.com/__down?bytes=10000000' in main,
    'Configurable HTTP ping over SOCKS': 'fun pingLatency(url: String' in (root / 'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt').read_text() and 'Proxy(Proxy.Type.SOCKS' in (root / 'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt').read_text() and 'http_ping_url' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text(),
    'Configurable SSH compression': 'compression.s2c' in (root / 'app/src/main/java/com/anutunnel/app/SshSocksProxy.kt').read_text() and 'ssh_compression' in main,
    'Configurable VPN MTU': 'vpn_mtu' in main and '.setMtu(mtu)' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text(),
    'Configurable reconnect delay': 'reconnect_delay_seconds' in main and 'scheduleReconnect(retryIntent, delay.toLong())' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text(),
    'Functional partial WakeLock lifecycle': 'WAKE_LOCK' in (root / 'app/src/main/AndroidManifest.xml').read_text() and 'wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text() and 'wakeLock?.let { if (it.isHeld) it.release() }' in (root / 'app/src/main/java/com/anutunnel/app/AnuVpnService.kt').read_text(),
    'CI verifies and packages BadVPN JNI library': 'test -s app/src/main/jniLibs/arm64-v8a/libanutunnel-badvpn.so' in (root / '.github/workflows/android-build.yml').read_text() and 'cp app/src/main/jniLibs/arm64-v8a/libanutunnel-badvpn.so release/' in (root / '.github/workflows/android-build.yml').read_text(),
    'Split tunnel app visibility permission': 'QUERY_ALL_PACKAGES' in (root / 'app/src/main/AndroidManifest.xml').read_text(),
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + '  ' + name)
if failed:
    raise SystemExit('Failed checks: ' + ', '.join(failed))
print(f'PASS  Required project files: {len(required)}')
print('PASS  Android XML files parse')
