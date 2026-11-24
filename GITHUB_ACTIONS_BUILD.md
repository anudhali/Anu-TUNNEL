# Anu TUNNEL — ARM64 cloud build

This revision adds a GitHub Actions build so native Android artifacts are compiled on an x86_64 Ubuntu runner rather than inside AndroidIDE's ARM64 terminal. This specifically avoids the observed `gomobile` panic `unsupported GOARCH: arm64` on the phone-side Linux host.

## Run it

1. Create a GitHub repository and upload/push this complete project folder to its ``main` or `feature/final-integration` branch.
2. Open the repository's **Actions** tab.
3. Select **Anu TUNNEL Android Build**.
4. Tap **Run workflow** and wait for the job to finish.
5. If the workflow succeeds, open its run page and download the artifact named **Anu-TUNNEL-Android-ARM64**. It contains the debug APK, DNSTT AAR, Hev compatibility library, BadVPN JNI library, and SHA256 checksums.

The workflow verifies and packages `libanutunnel-badvpn.so` in addition to the DNSTT AAR and Hev compatibility library. The workflow pins Go 1.24.4 and `golang.org/x/mobile` to the same revision for `gomobile` and `gobind`. It patches a writable temporary copy of the gomobile source so `gomobile init` does not unexpectedly install `gobind@latest` (which currently asks for a newer Go toolchain).

## Important limits

- A successful CI build proves compilation and packaging, not that a provider account or DNSTT server is configured correctly.
- The local SOCKS bridge is TCP CONNECT only. UDP packets use the separate BadVPN UDPGW client over an SSH direct-tcpip stream and require a compatible provider-side `badvpn-udpgw` service. A port-open test alone does not prove UDP traffic works.
- Smart Auto multi-server latency probing/failover is not implemented in this source milestone.
- BDIX Mode is not implemented in this source revision; a real Bangladesh BDIX-connected gateway and a verified routing path are required.
- Install and test the APK on the Redmi device with the intended Airtel SIM before calling the VPN operational.
- Do not put SSH passwords, private keys, or VPN account credentials in GitHub source, workflow logs, or public repository files.
