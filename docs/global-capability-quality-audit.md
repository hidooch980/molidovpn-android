# Molido Mesh AI — global capability and quality audit

Audit baseline: Android development branch, 2026-11-04. This is a source-code and test-inventory audit, not a physical-device certification or a claim that every feature has passed.

## Audit rules

Every capability is classified as:
- **Present (source verified):** implementation is visible in the current branch.
- **Partial:** some implementation exists, but at least one critical path, test, or platform integration is missing.
- **Test required:** source exists but runtime behavior is not proven by this audit.
- **Not evidenced:** no implementation was established from the inspected source; do not advertise it as available.

A protocol parser test is not a successful tunnel test. A server being listed or TCP-reachable is not proof of authorization, reliability, or end-to-end usability. No live endpoint is included in this audit.

## Findings from the Android source

| Area | Current evidence | Assessment |
|---|---|---|
| VPN lifecycle / TUN | Android VpnService and a single central MolidoVpnService | Present; test stop/restart and process death |
| Rust core | Aether Rust core, JNI bridge, QUIC/HTTP3, MASQUE and WireGuard dependencies | Present in source; per-transport runtime validation required |
| Device-aware link profile | Wi-Fi/cellular/Ethernet, validated/metered/roaming, local RTT/jitter/loss/throughput profile and bounded probes | Partial; physical device and handover validation required |
| Smart Connect / adaptive method | Quality scoring, network memory, method bandit, switch policy and stall detection | Present/partial; prove on controlled repeatable networks |
| IPv4 / IPv6 | Dual-stack test files and IPv6-capable core dependencies | Partial; DNS-only forwarder inspected here is IPv4-only |
| DNS-only | TUN routes resolver IPs; current DnsOnlyForwarder forwards IPv4 UDP/53 through protected sockets | Partial; no TCP fallback, no IPv6 packet forwarding, no encrypted upstream transport in this forwarder |
| DoH / DoT | DNS settings and other remote configuration references exist | Not verified end-to-end; do not call UDP resolver presets encrypted DNS |
| DNS leak prevention | TUN DNS setup and protected sockets in selected paths | Test required; leak-free behavior not proven |
| Kill switch | Must inspect OS-level always-on/block-without-VPN semantics and disconnect path | Not certified by this audit |
| Split tunneling | Existing routing/profile configuration and product docs | Partial; verify per-app and route policy on device |
| Protocol import | VLESS, VMess, Trojan, Shadowsocks, Hysteria2, XHTTP, personal WireGuard and MahsaNG fixtures | Parser coverage present; not all transports proven live |
| Psiphon / Tor | Source architecture and service integration | Present in source; ladder, bridge and leak regression required |
| Censorship resilience | Multiple Psiphon rungs, TLS retry/fragment tests, Hysteria2, MASQUE/QUIC | Partial; must test against distinct real network conditions, legally and with authorized servers |
| Server intelligence | catalog, health/shard probes, ranking and automatic refresh workflows | Partial; verify freshness, provenance, abuse handling and real endpoint health |
| Privacy-preserving telemetry | local device profile; some connection logs | Partial; audit every log/export for identifiers, DNS names, endpoints and credentials |
| Auto-update / signed release | release workflows and update-note tests exist | Test required; preserve signing key, verify signature and rollback before release |
| Android battery/background | foreground service and quality monitoring | Test required across Android versions and OEM power policies |
| iOS | SwiftUI + Packet Tunnel scaffold; Rust packet-channel bridge documented | Not production-ready until Swift FFI packet flow, entitlement, signing and device tests pass |
| Windows / Linux | core may build for selected targets, but end-user clients are not certified here | Not evidenced as production clients |
| Mesh / Bluetooth / Wi-Fi Direct | no end-to-end supported client mesh demonstrated in this audit | Not evidenced; separate design and device feasibility tests needed |

## Quality dimensions and mandatory benchmark suite

Run each case on at least: Wi-Fi, cellular, metered cellular, roaming (where available), captive portal, high RTT, jitter, packet loss, IPv4-only, IPv6-only and dual-stack. Repeat each run at least five times and report median plus p95; never rank by one speed test.

1. **Connection:** cold start, warm start, time to authenticated tunnel, time to first successful HTTPS request, handshake failure rate.
2. **Performance:** upload/download throughput, RTT median/p95, jitter, packet loss, DNS resolution latency, concurrent TCP and UDP behavior.
3. **Stability:** 30-minute idle, 60-minute mixed traffic, repeated Wi-Fi↔cellular handovers, tunnel restart, core crash recovery, server failover, packet reorder and MTU/fragmentation.
4. **Privacy/security:** IPv4/IPv6 public egress, DNS resolver and transport, DNS leak, WebRTC leak where relevant, kill switch during connect/disconnect, certificate validation, credential redaction, local storage encryption.
5. **Compatibility:** HTTP/2, HTTP/3, QUIC, WebSocket, UDP/VoIP, video, large downloads, captive portal, restrictive NAT and networks blocking UDP/443.
6. **Resource use:** battery drain per hour, CPU, memory, thermal state, radio wakeups and data overhead.
7. **Smart behavior:** route choice explainability, score calibration, false switches, switch time, dwell policy, rollback, per-network memory and no cross-network contamination.
8. **Update/supply chain:** APK signature, reproducible/versioned artifacts, dependency inventory, SBOM, release provenance, downgrade protection and rollback.
9. **Accessibility/product:** startup clarity, connection-state truthfulness, translations, screen reader, low-bandwidth mode and clear privacy controls.

## Benchmark protocol and acceptance thresholds

- Use identical device, OS, SIM/Wi-Fi, test destination, time window, protocol and server for A/B comparisons.
- Compare Molido against the relevant reference app (e.g. Cloudflare WARP, official WireGuard client, NekoBox/MahsaNG) only with lawful, user-authorized test configurations.
- Do not compare different exit regions as if they were protocol performance.
- Require >= 99% successful tunnel establishment across 100 controlled attempts before calling a transport stable; report confidence and environment.
- Require zero observed DNS/IP leakage in the defined test matrix before advertising leak protection; a finite test does not prove universal absence.
- Require no silent plaintext downgrade when DoH/DoT is selected.
- Require no false Connected state: UI must wait for core readiness and a successful data-plane probe.
- Require no unbounded battery, memory, queue or retry growth.
- Publish raw anonymized measurements and test conditions, not user DNS questions, browsing history or credentials.

## Priority implementation gates

### P0 — security correctness
1. Replace/extend DNS-only path for IPv4 and IPv6 and UDP/TCP DNS handling; make resolver transport explicit.
2. Implement DoH/DoT with hostname/certificate validation, timeout, bounded retry and no silent plaintext downgrade.
3. Prove DNS/IP leak protection and kill-switch semantics during start, failure, handover and stop.
4. Audit logging and secret storage; redact node credentials and sensitive endpoints.

### P1 — connection quality
1. Finish device-specific history weighting in Smart Connect and validate network-key isolation.
2. Add repeatable benchmark harness with median/p95, first-byte time, throughput, loss, jitter and battery.
3. Tune MASQUE/QUIC and WireGuard per network; add TCP/TLS fallback only where supported by the engine and policy.
4. Add MTU/path-MTU, captive portal, NAT rebinding and UDP-blocked-network tests.

### P2 — platform and operations
1. Complete Android OEM/background and signed update/rollback tests.
2. Complete iOS Swift packet bridge, Network Extension entitlement and physical-device tests.
3. Establish Windows/Linux clients before claiming desktop support.
4. Implement opt-in, privacy-minimized mesh discovery and revocable node trust only after abuse/security review.

## External standards and product patterns reviewed

- IETF MASQUE working group and RFCs: CONNECT-UDP (RFC 9298), CONNECT-IP (RFC 9484), HTTP Datagrams (RFC 9297); active work includes DNS/PREF64, ECN/DSCP, datagram compression and QUIC-aware proxying. Treat Internet-Drafts as drafts, not finalized standards.
- Cloudflare WARP modes: DNS-only with encrypted DoH/DoT, traffic-only, and traffic+DNS; WARP docs describe post-quantum protection in its own service. Do not copy claims or assume equivalent protection in Molido.
- WireGuard: retain as a simple, auditable fast transport; do not bolt proprietary behavior into the WireGuard protocol itself.
- NekoBox/MahsaNG: reuse standard profile import compatibility where tested; do not depend on private subscription APIs or reuse donated nodes without permission.

References:
- https://datatracker.ietf.org/wg/masque/documents/
- https://www.iana.org/assignments/masque
- https://developers.cloudflare.com/warp-client/warp-modes/
- https://developers.cloudflare.com/warp-client/privacy/
- https://www.wireguard.com/protocol/
- https://github.com/MatsuriDayo/NekoBoxForAndroid
- https://github.com/GFW-knocker/MahsaNG

## Audit conclusion

Molido already contains a substantial Android network core and a meaningful set of adaptive, protocol and resilience components. It is not yet justified to describe every modern capability as production-ready. The most important gaps identified from source are encrypted DNS integration, complete DNS-only dual-stack behavior, proof of leak/kill-switch guarantees, repeatable device benchmarks, and completion of iOS packet flow. Work should proceed through the P0/P1 gates and the latest CI/device evidence, not by adding protocol names to the UI.
