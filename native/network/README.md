# Android network regression probe

This isolated APK stages the production Tun2SocksJniLoader.java and builds the
production badvpn/lwIP CMake target. Unix datagram socketpairs model TUN packets
and a loopback SOCKS/UDPGW server echoes them. No VPN permission, public endpoint,
subscription, account or credential is needed.

On Android API 28 and 35 it checks IPv4/IPv6 TCP and UDP payload round trips,
checksums, MTU bounds, SOCKS destination families, AAAA DNS through transparent
IPv4 DNS, controlled 120 ms gateway delay, two native start/stop cycles and Tor's
DNS-only UDP gate. Reports include source/APK/native-library hashes. Missing or
crashing probes fail the workflow.

## Limits

These are native packet fixtures, **not an OS VPN/kill-switch leak test**, a
production-server benchmark or an Iran mobile-network/media-playback result.
Separate JVM tests cover actual Android VPN Builder state and TUN ownership.
System always-on lockdown, process death, permission revocation, carrier
handover, WebRTC/STUN, DNS interception and long Instagram/WhatsApp/Telegram
sessions still need the installed app and actual carrier. Explicit LAN, Iran
and app exclusions are intentional direct traffic.
