# Molido transport compatibility lab

## Purpose

This document is the source-audited implementation inventory and test contract for the twelve requested options. It deliberately separates code presence from real-world interoperability. A parser, native library or tunnel path is not proof that a given server, carrier or Android device works.

The machine-readable inventory is \`config/network/transport-catalog.json\`. Its entries are descriptive only: they do not activate, select, download or execute any transport.

## Correct protocol layers

| Layer | Options | Meaning |
|---|---|---|
| Proxy / overlay | MTProto Proxy, Tor | Application-oriented proxy or anonymity network; neither is a generic base VPN transport |
| Base transport | TCP, UDP | Stream and datagram carriage |
| UDP transport protocol | QUIC | Reliable encrypted transport over UDP |
| Tunnel/application framing | WebSocket, gRPC, XHTTP, HTTPUpgrade | Protocol-specific framing that must match the server implementation |
| HTTP version | HTTP/2, HTTP/3 | HTTP/2 normally uses TCP+TLS; HTTP/3 uses QUIC over UDP |
| Specialized UDP tunnel | mKCP | Legacy KCP-based transport; must be checked against the exact bundled engine version |

Tor carries TCP streams through SOCKS and does not provide general-purpose UDP forwarding. MTProto Proxy is intended for Telegram's MTProto traffic and must not be presented as a whole-device VPN mode.

## Source audit: current Molido implementation

The following is based on the checked-in client source and unit-test files, not a live-server claim.

| Option | Source status | Evidence in the repository | Remaining validation |
|---|---|---|---|
| MTProto Proxy | Not found in current client source scan | No adapter identified | Decide whether to add a Telegram-only proxy mode; test MTProto client/server interoperability |
| Tor | Existing client path | \`TorManager.kt\`, \`TorSocksFront.kt\` | Real-device bridge bootstrap, DNS, reconnect and soak matrix |
| TCP | Existing client path | V2Ray/Xray network parser; smoltcp TCP sockets | Profile/server matrix and network-switch tests |
| UDP | Existing client path | smoltcp UDP sockets; sing-box Hysteria2/TUIC sidecar | NAT, UDP-blocking, loss and server compatibility matrix |
| QUIC | Existing Aether path | quiche-based \`quic.rs\` and MASQUE | Gateway interoperability, UDP loss, NAT and battery tests |
| WebSocket | Existing Xray/V2Ray path | \`V2rayNodes.kt\` parser and \`wsSettings\` builder | Matching server path, TLS, CDN/proxy and idle-timeout tests |
| gRPC | Existing Xray/V2Ray path | \`grpcSettings\` outbound builder | Service-name/mode interoperability and streaming tests |
| XHTTP | Existing Xray/V2Ray path | \`XhttpProfile.kt\`, \`xhttpSettings\` builder | Pinned Xray version, request modes and real-server matrix |
| HTTP/2 | Existing experimental Aether path | \`masque_h2.rs\` | Known gateway interoperability limitations; do not assume general availability |
| HTTP/3 | Existing Aether path | quiche HTTP/3 connection in \`quic.rs\` | Live gateway and network-profile validation |
| HTTPUpgrade | Existing Xray/V2Ray path | Parser and \`httpupgradeSettings\` builder | Matching server configuration and reverse-proxy tests |
| mKCP | Not found in current client source scan | Current V2Ray network allow-list does not include it | Verify bundled Xray support and maintained server compatibility before implementation |

Relevant existing tests include \`VlessTransportTest.kt\`, \`XhttpProfileTest.kt\`, \`Hysteria2ImportTest.kt\`, \`WartimeTunnelsTest.kt\`, and Rust unit tests in \`core/aether/src/quic.rs\` and \`core/aether/src/masque_h2.rs\`. These are source/unit tests; they do not replace end-to-end tests against controlled servers.

## Adapter and server contract

Before adding a new adapter, record:

1. Exact client engine and version, build flags, licence and update source.
2. Exact server implementation and version, configuration schema and deployment ownership.
3. Android API levels, CPU ABIs, minimum memory and native-library size.
4. IPv4/IPv6, DNS, MTU, TCP/UDP, multiplexing and reconnect behavior.
5. Certificate and peer identity validation; no insecure fallback.
6. Resource, battery and background execution budgets.
7. Import/export compatibility and safe failure behavior.

A client feature is not usable without a compatible server endpoint. The agent must not download or execute native code or configuration-supplied executables.

## Mandatory compatibility and performance gates

Run a client/server pair on at least two independent network profiles. Keep results separated by device, Android version, carrier/Wi-Fi, server region and build.

| Dimension | Required evidence |
|---|---|
| Handshake and compatibility | Successful auth, protocol negotiation, server version, TLS/ALPN where relevant |
| Routing correctness | IPv4 and IPv6 reachability, DNS behavior, no DNS/IP leak, correct MTU |
| Speed | At least 10 repeated upload/download samples; median and p95, not a single peak |
| Latency | Idle and loaded RTT; median and p95 |
| Stability | At least 30-minute soak; reconnect after Wi-Fi/cellular switch; foreground/background transition |
| Loss and NAT | Packet loss, reordering and NAT behavior for UDP-based transports |
| Battery/resources | CPU, memory and battery drain under the same fixed workload |
| Security | Certificate validation, no plaintext fallback, no secret logging, no traffic leak |

### Promotion states

- **Source present:** client code exists; no runtime quality claim.
- **Unit tested:** parser/adapter logic tests pass; still no live-server claim.
- **Interop tested:** compatible server tests pass on at least two network profiles.
- **Validated:** all security, compatibility, stability and performance gates pass with reproducible evidence.
- **Disabled / rejected:** any routing leak, certificate bypass, crash, data corruption or repeated disconnect.

No transport in this catalog is runtime-selectable by virtue of being listed. Existing app routes continue to be controlled by their existing configuration and server capability checks. New modes must remain opt-in until the validation gate is satisfied.

## Remaining implementation sequence

1. Preserve and regression-test the already integrated Tor, TCP/UDP, QUIC, WebSocket, gRPC, XHTTP, HTTPUpgrade and HTTP/3 paths.
2. Keep HTTP/2 experimental until the gateway interoperability issue is retested with controlled endpoints.
3. Evaluate mKCP only after confirming the bundled Xray engine still supports it and identifying a maintained server implementation. Add parser/configuration/tests only then.
4. Treat MTProto Proxy as a separate Telegram-only proxy feature, not a VPN transport. Add it only with a maintained client implementation and a clearly scoped UI/traffic model.
5. Run the real-device matrix and record signed benchmark reports before enabling any newly added option in Smart Connect.
6. Compare protocol performance only within equivalent device/network/server cohorts; never rank measurements from unrelated environments as directly comparable.

## Agent responsibilities and safety

Molido Agent may refresh the catalog, collect sanitized metrics, run approved test suites, detect regressions and open a reviewable pull request. It may not mark untested transports as supported, change VPN security defaults, silently enable a transport, execute remote code, or publish a release outside the normal CI, review and signing gates.
