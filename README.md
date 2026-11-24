# Anu TUNNEL — Android Kotlin project

**Package:** `com.anutunnel.app`  
**Version:** `1.8.0` (source milestone; not yet device-certified)  
**Target:** Android API 24+; `arm64-v8a`; target SDK 35.

## Active packet path

```text
Android apps
  -> Android VpnService TUN descriptor
  -> BadVPN tun2socks + UDPGW JNI engine
  TCP: local SOCKS5 TCP CONNECT (127.0.0.1:1080)
       -> SSH direct-tcpip (JSch) -> SSH over DNSTT
  UDP: BadVPN UDPGW framing -> local SOCKS5 TCP CONNECT
       -> SSH direct-tcpip -> provider badvpn-udpgw on SSH host
```

The native BadVPN packet engine is built from the pinned upstream BSD-3-Clause source. The Android JNI adapter is project code. The older Hev JNI library is retained as a compatibility artifact; the active service starts BadVPN.

## Native build

`tools/build_native.sh` builds:
1. The DNSTT Go client bridge as `app/libs/dnstt.aar`.
2. The Hev ARM64 compatibility library.
3. The BadVPN tun2socks/UDPGW ARM64 JNI library.

Requirements: Git, Go 1.24+, Java/JDK 17, Android SDK Platform 35, Android NDK, CMake and Python 3. The script enables Go toolchain auto-download. On AndroidIDE ARM64, use GitHub Actions for native compilation because gomobile's host toolchain may not run on the phone's ARM64 Linux environment.

## Build

The source ZIP does not include `gradle-wrapper.jar`. Use GitHub Actions (recommended) or AndroidIDE's installed Gradle task runner after installing JDK 17, SDK Platform 35 and NDK.

GitHub Actions workflow: `.github/workflows/android-build.yml`. It builds DNSTT, Hev compatibility, BadVPN JNI and the ARM64 debug APK. The uploaded artifact includes the APK, native artifacts and SHA-256 checksums. A successful CI build confirms packaging only, not provider connectivity.

## Profile and provider requirements

- SSH account format: `SSH_USERNAME:SSH_PASSWORD`.
- The profile's DNSTT domain and public key must match the provider's DNSTT service.
- The provider's DNSTT server must forward its stream to the provider's SSH service.
- UDP requires a compatible `badvpn-udpgw` daemon already running on SSH-host loopback at the exact TCP port supplied by the provider. The app does not install or configure that remote daemon.
- Saved profile credentials are encrypted at rest with Android Keystore AES-GCM. JSON exports include credentials and must be kept private.
- SSH host keys use trust-on-first-use pinning. Verify the first fingerprint with the provider when possible.

## Implemented in source

See `BUILD_STATUS.md` for the complete implementation boundary. This source includes Global Mode, Smart Auto DNS-resolver selection, SSH/DNSTT TCP path, BadVPN UDPGW client path, split-tunnel exclusions, live SOCKS TCP counters, configurable HTTP Ping over SOCKS, optional SSH zlib compression, configurable VPN MTU, configurable Auto Reconnect delay, optional partial WakeLock, and SOCKS-routed speed/IP checks.

## Not implemented / not claimed complete

- BDIX Mode is not implemented; it requires a real BDIX-connected gateway and verified route.
- VMess, VLESS, Trojan and Shadowsocks have no protocol-compatible core integrated in this revision.
- Smart Auto does not perform full endpoint handshake/failover; it only selects the fastest configured UDP DNS responder.
- A UDPGW TCP port being open or its SOCKS stream being connected does not prove UDP end-to-end. Native state `2` is the valid UDPGW reply signal.
- Other requested features such as TLS wrapping, provider payload modes, DNS primary/secondary/fallback, configurable local proxy ports, LAN access, buffer tuning and a performance mode are not implemented unless separately validated.

## Validation

Source assertions, shell syntax, Python syntax and Android XML parsing pass in the current source-validation environment. No Android SDK/NDK or Gradle installation is available here, so this source milestone has not been compiled into an APK. A physical Android device and real provider account are still required for packet-path, reconnect, lifecycle, DNS, TCP, UDP and throughput testing.

Do not call the release complete until CI build, installation, provider-backed TCP/UDP tests, reconnect/network-handover tests and disconnect cleanup all pass on the target device.
