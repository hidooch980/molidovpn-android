# Molido transport compatibility lab

## Purpose and status

This is a test contract and implementation plan, not a claim that these transports are already implemented. Every catalog entry is `planned` and disabled. A protocol must not appear in Smart Connect or become selectable until a compatible client adapter, compatible server endpoint, and passing device/network test results are all present.

## Correct layering

- **Proxy / overlay systems:** MTProto Proxy and Tor. They are not interchangeable with a VPN transport. Tor carries TCP streams; it does not provide general UDP forwarding.
- **Base transports:** TCP and UDP. QUIC runs over UDP and supplies its own encrypted, reliable transport semantics.
- **Application/tunnel transports:** WebSocket, gRPC, XHTTP and HTTPUpgrade. They require a defined compatible tunnel protocol and server configuration.
- **HTTP protocol versions:** HTTP/2 generally uses TCP+TLS; HTTP/3 uses QUIC over UDP. They are not standalone VPN protocols.
- **Specialized UDP tunnel:** mKCP. Treat as optional/legacy until maintained client and server implementations are identified and benchmarked.

The machine-readable source of truth is `config/network/transport-catalog.json`.

## Adapter contract

Each adapter must declare:
1. Stable ID and implementation/version.
2. Supported Android API levels and CPU ABIs.
3. Required server-side implementation and configuration schema.
4. Supported IP families, DNS behavior, IPv4/IPv6 routing, and MTU constraints.
5. Whether UDP, TCP, streaming, multiplexing and proxy chaining are supported.
6. Resource limits, permissions, and failure/reconnect behavior.

A catalog entry alone does not enable an adapter. No dynamic native library or remote executable code may be downloaded and run by the agent.

## Required test gates

Run each compatible client/server pair on the same device and test network profile. Record raw results, app build, adapter version, server build/region, Android version, carrier/Wi-Fi, test time, and sample count.

| Dimension | Minimum evidence |
|---|---|
| Compatibility | Handshake, auth, server version, TLS/ALPN where relevant, IPv4 + IPv6, DNS, MTU, Android API/ABI |
| Speed | Repeated download/upload throughput tests; report median and p95, not a single peak |
| Latency | Idle and loaded RTT; median and p95 |
| Stability | At least 30-minute soak, reconnect after network switch, packet loss and background/foreground transitions |
| Battery/resources | CPU, memory and battery drain over a fixed workload |
| Security | Certificate/identity validation, no plaintext fallback, no secret logging, DNS/IP leak checks |

### Promotion policy

- **Pass:** all mandatory compatibility/security checks pass and performance thresholds are met on at least two independent network profiles.
- **Conditional:** works on a limited server/network profile; keep opt-in and label the limitation.
- **Fail:** any security, routing leak, crash, data corruption or repeated disconnect; disable selection.
- Never rank protocols using measurements from different devices, server regions, or test windows as if they were directly comparable.
- Smart Connect may select only adapters marked `validated` by signed/local test evidence and supported by the selected server. Keep a last-known-good route for rollback.

## Initial implementation sequence

1. Inventory existing dependencies and server-side support; do not duplicate an existing engine.
2. Build adapter interface and capability negotiation.
3. Add repeatable instrumentation and compatibility test fixtures.
4. Validate TCP and UDP baselines first.
5. Add QUIC and HTTP/2/HTTP/3 only with compatible endpoints.
6. Add WebSocket, gRPC, XHTTP and HTTPUpgrade as separate tunnel adapters.
7. Evaluate MTProto Proxy and Tor as optional proxy modes, not as universal VPN replacements.
8. Evaluate mKCP last, only if there is a maintained, interoperable endpoint and a measurable use case.
9. Enable each individually after CI and real-device/network gates pass.

## Agent responsibilities

The Molido agent may collect sanitized metrics, detect regressions, run approved test suites, compare results, and open a focused pull request or issue. It must not mark an untested protocol as supported, change security defaults, download/execute remote code, or publish a release without the normal review, CI, signing, and release gates.
