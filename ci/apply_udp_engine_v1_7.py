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
patch("app/src/main/java/com/anutunnel/app/AnuVpnService.kt",
 'publishState("CONNECTED", "SSH/DNSTT + BadVPN UDPGW · localhost:$udpgwPort reachable")',
 'publishState("CONNECTED", "SSH/DNSTT + BadVPN UDPGW client started; remote UDP replies still require provider verification")',
 "truthful connected detail")
print("PASS: provider UDPGW port is no longer silently defaulted to 7300")
