# Anu TUNNEL v1.8.0 — implementation status

This file separates source-implemented behavior from provider-dependent behavior and work that is still missing. A control in the UI is not evidence that a protocol or packet path works.

## Implemented in source (not yet device-certified)

- Android `VpnService` TUN interface with IPv4/IPv6 default routes and a configurable MTU (576–9000; default 1400).
- DNSTT Go mobile client bridge, SSH password authentication over the DNSTT local stream, SSH host-key trust-on-first-use pinning, and a local SOCKS5 TCP CONNECT listener.
- BadVPN tun2socks/UDPGW native packet engine for ARM64, built from pinned upstream source. The Android app does not bundle or configure a provider-side UDPGW daemon.
- Local profiles with create/edit/save/delete/select/default/reset and JSON import/export. Saved SSH credentials are encrypted with Android Keystore AES-GCM. Exported JSON contains credentials and must be kept private.
- Global Mode uses the selected provider profile. Smart Auto tests configured UDP DNS resolvers and chooses the lowest-latency responder; it does not test complete SSH/DNSTT sessions or perform endpoint failover.
- Split-tunnel app exclusions are passed to Android `VpnService`.
- Live upload/download counters are measured from actual SOCKS TCP streams.
- Configurable HTTP Ping URL. The HTTP request is sent through the local SOCKS5 → SSH → DNSTT path while connected; it measures an HTTP response, not ICMP.
- Configurable Auto Reconnect delay (2–120 seconds) for failed connection attempts. Network restoration triggers a reconnect attempt when Auto Reconnect is enabled.
- Optional SSH zlib compression negotiation.
- Optional partial WakeLock while connected; it is released during service cleanup.
- Connected speed test and public IP lookup use the local SOCKS proxy while connected.
- GitHub Actions builds DNSTT, Hev compatibility library, BadVPN JNI, and the ARM64 debug APK; the artifact includes all three native deliverables.

## Not implemented / not claimed complete

- **BDIX Mode:** not implemented in this source. It requires a real BDIX-connected gateway/provider endpoint and a verified routing path. A mode selector alone would be misleading.
- **VMess, VLESS, Trojan, Shadowsocks:** no protocol-compatible core is integrated in this source. These need a maintained engine (for example, sing-box/libbox) plus profile parsing, lifecycle integration, TUN packet forwarding, and protocol-specific tests. Do not label them working until those checks pass.
- **Full Smart Auto failover:** current Smart Auto only selects a DNS resolver; it does not establish and compare complete DNSTT+SSH or other protocol sessions.
- **HTTP Ping URL reachability:** the feature measures the configured HTTP endpoint through SOCKS; the endpoint must support a successful GET response. It is not an ICMP ping.
- **Provider-side UDPGW:** the SSH server must already expose a compatible `badvpn-udpgw` process on `127.0.0.1:<provider-supplied-port>`. A TCP port-open test or native stream-up state is not proof that real UDP packets have completed a round trip. Native state 2 is the valid UDPGW reply signal.
- **BDIX gateway discovery, SSH payload obfuscation, TLS wrapping, DNS primary/secondary/fallback, configurable local proxy ports, LAN access, per-app include mode, buffer tuning, and full traffic-forwarding performance profiles** are not implemented unless separately verified in source and on device.

## Validation boundary

- `python3 tools/verify_source.py`: source assertions and Android XML parsing.
- `bash -n tools/*.sh`: shell syntax.
- These checks do not compile Kotlin, build native binaries, build an APK, or prove network connectivity.
- This source ZIP does not include the Gradle wrapper JAR. GitHub Actions installs Gradle on its runner.
- The current execution environment has no Android SDK/NDK or Gradle executable, so no local APK build was performed.
- A successful GitHub Actions run proves compilation and packaging only. A physical Android device and provider account are still required to verify DNSTT handshake, SSH authentication, TCP/UDP traffic, DNS, reconnect, battery behavior, and disconnect cleanup.

## Release gate

Do not call this app fully complete until:
1. GitHub Actions builds the APK and all required native libraries.
2. The APK installs and launches on the target Android device.
3. Provider-backed DNSTT and SSH authentication succeed.
4. TCP and UDP packet paths are tested separately; UDPGW native state reaches 2 after a valid reply.
5. Network handover, Auto Reconnect, HTTP Ping, split tunneling, MTU, WakeLock release, and disconnect cleanup are tested on-device.
6. Each additional protocol has a matching core and a successful real endpoint test.


## Tunnel type selector status (v1.8.1 update)
- BDIX is not offered as a network mode; legacy saved `BDIX Mode` preference is normalized to `Global Mode`.
- New profile editor selector lists SSH, SSH Through DNSTT, VMess, VLess, Trojan, Shadowsocks, and V2Ray.
- `SSH Through DNSTT` remains the default profile type and the only selectable type currently allowed to start the existing service.
- Other types are explicitly blocked with an explanatory message; their UI entries are not represented as working protocol cores. VMess/VLESS/Trojan/Shadowsocks/V2Ray require actual compatible core integration and endpoint testing before enablement.
- The existing VPN service still uses the SSH + DNSTT path; this update does not add sing-box/libbox.
