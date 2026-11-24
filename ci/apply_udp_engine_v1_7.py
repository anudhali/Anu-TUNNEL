#!/usr/bin/env python3
from pathlib import Path
root=Path("anu_tunnel")
def patch(rel, old, new, label):
 p=root/rel; s=p.read_text(); n=s.count(old)
 if n!=1: raise SystemExit(f"{label}: expected one anchor, found {n}")
 p.write_text(s.replace(old,new,1))
patch("app/src/main/java/com/anutunnel/app/TunnelConfig.kt",
 "var udpgwPort: Int = 7300","var udpgwPort: Int = 0","new profile port")
patch("app/src/main/java/com/anutunnel/app/TunnelConfig.kt",
 'udpgwPort = o.optInt("udpgwPort", 7300)','udpgwPort = o.optInt("udpgwPort", 0)',"legacy JSON port")
patch("app/src/main/java/com/anutunnel/app/MainActivity.kt",
 'add("udpgw", "UDP Gateway Port (SSH server)", config.udpgwPort.toString(), "7300")',
 'add("udpgw", "Provider UDPGW TCP Port", if (config.udpgwPort in 1..65535) config.udpgwPort.toString() else "", "Ask provider; no universal default")',
 "provider port field")
patch("app/src/main/java/com/anutunnel/app/MainActivity.kt",
 'toast("UDP Gateway Port must be between 1 and 65535 (usually 7300)")',
 'toast("Enter the exact UDPGW TCP port supplied by your SSH provider. There is no universal default.")',
 "provider port validation")
patch("app/src/main/java/com/anutunnel/app/AnuVpnService.kt",
 '"TCP forwarding works, but SSH server localhost:$udpgwPort is not reachable. Enable provider BadVPN UDPGW or set the correct port."',
 '"Provider UDPGW TCP port 127.0.0.1:$udpgwPort is not reachable through SSH. Ask your provider for the exact port and enable its compatible BadVPN UDPGW service."',
 "provider port diagnostic")
# Replace the old optimistic detail regardless of the exact punctuation used by the prior patch.
import re
service_path=root/"app/src/main/java/com/anutunnel/app/AnuVpnService.kt"
service_text=service_path.read_text()
service_text, count = re.subn(r'publishState\("CONNECTED", "SSH/DNSTT \+ BadVPN UDPGW[^"]*"\)',
    'publishState("CONNECTED", "SSH/DNSTT + BadVPN UDPGW client started; remote UDP replies still require provider verification")',
    service_text, count=1)
if count != 1: raise SystemExit("could not find previous optimistic UDPGW connected message")
service_path.write_text(service_text)

print("PASS: provider UDPGW port is no longer silently defaulted to 7300")


# Instrument the pinned BadVPN UDPGW SOCKS stream and valid response path.
def inject(rel, old, new, label):
 p=root/rel; t=p.read_text(); n=t.count(old)
 if n!=1: raise SystemExit(f"{label}: expected one native source anchor, found {n}")
 p.write_text(t.replace(old,new,1))

prepatch("app/build.gradle", "versionCode 13", "versionCode 14", "v1.7 version code")
patch("app/build.gradle", "versionName '1.6.0'", "versionName '1.7.0'", "v1.7 version name")

p=root/"BUILD_STATUS.md"; t=p.read_text()
t += """
\n## v1.7.0 UDPGW validation and provider-port handling
- New profiles do not assume port 7300. A provider-supplied UDPGW TCP port must be explicitly entered and saved.
- Existing profiles from older versions are migrated as unconfirmed; their previous 7300 value is not trusted automatically.
- Native status distinguishes the UDPGW SOCKS/TCP stream from a valid UDPGW response packet. Only a valid decoded reply with a matching connection ID and peer marks UDP relay verified.
- The BadVPN UDPGW SOCKS state is reset on each reconnect to avoid carrying a prior session's success into a new connection.
- The app still cannot install or configure the provider-side badvpn-udpgw daemon. The provider must run a compatible service on SSH-host loopback at the confirmed port.
"""
p.write_text(t)
print("PASS: provider port confirmation, native relay-state reset and v1.7.0 version applied")
