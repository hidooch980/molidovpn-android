# Cloudflare 1.1.1.1 / WARP product patterns for Molido

Review date: 2026-11-04. This is a product/architecture review, not a claim that Molido currently implements Cloudflare WARP or has been benchmarked against it.

## What the product actually offers

Cloudflare separates DNS resolver service from its WARP traffic tunnel. Its current WARP documentation describes DNS-only (DoH or DoT), traffic-only, and combined traffic+DNS modes. The WARP tunnel uses MASQUE by default and also supports WireGuard. WARP is not an anonymity service and is not intended to make a user appear to be in another country. Sources: https://developers.cloudflare.com/warp-client/warp-modes/ ; https://developers.cloudflare.com/warp-client/ ; https://developers.cloudflare.com/warp-client/known-issues-and-faq/ ; https://developers.cloudflare.com/warp-client/get-started/linux/ .

## What Molido already has (do not duplicate)

- DNS preset selection exists in DnsSettings.kt.
- A DNS-only TUN/forwarder path exists in DnsOnlyForwarder.kt and MolidoVpnService.kt. It currently forwards IPv4 UDP/53 to configured resolver IPs using a protected socket; it is not DoH or DoT.
- Full VPN transport engines, split-tunnel settings and device/network quality profiling already exist.

Important security distinction: configuring 1.1.1.1 or 1.0.0.1 as a plain resolver IP does not itself enable encrypted DNS. Do not label that mode 'secure DNS' unless the upstream transport is actually DoH/DoT and certificate/hostname validation succeeds. The existing UDP forwarder must not be described as encrypted DNS.

## Patterns worth adopting

1. **Three explicit connection intents:** DNS only (device traffic remains direct); tunnel traffic only (DNS remains OS-managed); tunnel + protected DNS. Present these as separate modes with clear status labels so users know exactly what is protected.
2. **Resolver transport as a first-class choice:** Automatic, DoH, DoT and (only when explicitly selected) UDP. For DoH/DoT validate TLS certificates and hostnames, support IPv4/IPv6 endpoints, bounded timeouts, fallback and captive-portal handling. Never silently downgrade an encrypted selection to plaintext.
3. **Split tunneling with visible policy:** include/exclude apps and routes, explain which traffic bypasses the tunnel, and protect the tunnel control-plane endpoints from routing loops.
4. **Network-aware protocol choice:** compare MASQUE and WireGuard per device/network using measured handshake time, RTT, jitter, loss, throughput, battery and reconnect success. Keep the user's choice available; do not assume one protocol wins on every carrier.
5. **Privacy controls and honest diagnostics:** show DNS transport, tunnel mode, selected protocol and last health check; keep telemetry minimal, aggregate where possible, make diagnostics export opt-in, and never log DNS question names or payloads by default.
6. **Family filtering as an optional resolver policy:** offer malware-only and malware+adult filtering only with a clear provider and scope explanation; do not silently impose filtering.

## Molido implementation decision

- Keep Molido provider-independent: Cloudflare 1.1.1.1 may be an optional DoH/DoT resolver endpoint, never a mandatory dependency or a source of VPN exit nodes.
- Add a dedicated encrypted-DNS adapter rather than adding Cloudflare IPs to the existing UDP-only gaming DNS preset list.
- Reuse the existing DNS-only and full-tunnel paths; unify their mode/status model rather than building a second VPN engine.
- Add an explicit 'DNS only' / 'VPN + secure DNS' / 'VPN with system DNS' mode selector only after the service routing and leak tests prove the selected semantics.
- Do not claim WARP+ routing acceleration or post-quantum protection for Molido unless a separately implemented and verified transport provides those properties.

## Required acceptance tests before release

- DNS-only: ordinary TCP/UDP traffic retains the device's direct public route; only resolver traffic uses the configured resolver.
- DoH/DoT: packet capture confirms no plaintext DNS leakage; invalid certificates, wrong hostnames and timeouts fail closed or use an explicitly consented encrypted fallback.
- Full tunnel: IPv4 and IPv6 DNS and application traffic follow the selected tunnel policy; kill switch behavior is verified during disconnect and network handover.
- Traffic-only: DNS behavior is exactly the OS/system resolver behavior and is labeled accordingly.
- Split tunnel: included/excluded apps and control-plane routes behave as displayed, with no routing loops.
- Captive portal, airplane mode, Wi-Fi-to-cellular handover, metered/roaming networks, battery use and recovery after process death.

## Source references

- WARP modes: https://developers.cloudflare.com/warp-client/warp-modes/
- WARP client overview: https://developers.cloudflare.com/warp-client/
- WARP privacy: https://developers.cloudflare.com/warp-client/privacy/
- Linux client mode/protocol controls: https://developers.cloudflare.com/warp-client/get-started/linux/
- 1.1.1.1 vs WARP explanation: https://www.cloudflare.com/learning/dns/what-is-1.1.1.1/
