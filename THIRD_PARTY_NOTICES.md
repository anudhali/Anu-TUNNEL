# Third-party components

Anu TUNNEL's native build script fetches these upstream projects at build time:

- **DNSTT mobile client:** https://github.com/dnstt-xyz/dnstt_xyz_app — upstream repository declares CC0/public-domain licensing for the DNSTT Go source. The upstream Go source and its own `COPYING` file are fetched intact into `.native-build/` at build time.
- **hev-socks5-tunnel:** https://github.com/heiher/hev-socks5-tunnel — MIT License. Source and submodules are fetched intact at build time.
- **JSch maintained fork:** `com.github.mwiede:jsch` — BSD-style license; resolved from Maven Central by Gradle.

Review the upstream license files and dependency notices again before distributing a release APK.

- **BadVPN tun2socks / UDPGW client:** https://github.com/ambrop72/badvpn — upstream BSD 3-Clause license; full text is bundled at app/src/main/assets/licenses/BadVPN-COPYING.txt. Anu TUNNEL builds the upstream client from source and adds a small Android TUN-fd/JNI adapter; no Dark Tunnel APK code or binaries are reused.
