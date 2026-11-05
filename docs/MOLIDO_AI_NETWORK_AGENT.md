# Molido AI Network Agent

Status: architecture proposal and implementation contract. This document does not claim that the agents described below are already implemented.

## Product goal

Build a local-first, privacy-preserving network intelligence system that improves connection selection, recovery, gaming quality, diagnostics and user control. It must remain useful when the device is offline and must never make the VPN data plane depend on an LLM, cloud API or agent service.

## Design principles

1. **Deterministic data plane:** Kotlin VPN lifecycle and Rust transport remain authoritative for tunnel creation, packet forwarding, DNS routing, kill switch and protocol capability.
2. **AI advises; policy authorizes:** agents produce ranked recommendations with evidence and confidence. A deterministic policy gate validates every action against user settings, security invariants and protocol capability flags.
3. **Local-first learning:** raw destinations, packet payloads, browsing history, DNS query names and credentials are never collected for model training. Store only bounded aggregate measurements and user-approved preferences.
4. **Graceful degradation:** if an agent, model, storage layer or remote service is unavailable, existing Smart Connect and deterministic fallback behavior continue unchanged.
5. **Explainability:** every recommendation records the measured reason, confidence, constraints and outcome in a user-readable diagnostic event.
6. **No fabricated capability:** a parser, profile importer or generic reachability probe must never be represented as a working tunnel or game-server measurement.

## Agent topology

The orchestrator is a small Kotlin policy coordinator. Specialized agents are bounded modules with typed inputs/outputs; they are not autonomous processes with unrestricted access.

| Agent | Responsibility | Inputs | Permitted outputs |
|---|---|---|---|
| Network Intelligence | Estimate current network quality and stability | RTT samples, p95, jitter, loss, transport success, network type | Quality classification and confidence |
| Smart Route | Rank already-supported routes and gateways | Measured route metrics, capability registry, user region preference | Candidate ranking and reason codes |
| Protocol Advisor | Recommend supported transports for current conditions | Verified capability flags and recent probe outcomes | Ranked supported protocol IDs only |
| Gaming Optimizer | Prioritize stable low-latency routes | Route p95, jitter, loss, stability and recovery samples | Gaming ranking; never claim in-game ping without game endpoint telemetry |
| Recovery / Self-healing | Detect stalls and select bounded recovery steps | Tunnel health, watchdog events, recent failure history | Retry, re-probe or switch request within policy limits |
| Security Guardian | Check security invariants before a change | DNS mode, IPv4/IPv6 routes, kill-switch state, certificate and signature status | Allow/deny decision with reason |
| Diagnostics | Explain failures and produce actionable reports | Sanitized state transitions and error codes | Local explanation and support bundle |
| Learning & Personalization | Learn per-network/per-time preferences | Aggregated outcomes and explicit user preferences | Local weights and preference suggestions |
| Battery & Data Steward | Balance probe frequency and data use | Battery saver, metered network, thermal state, user mode | Probe budget and scan cadence |
| Privacy Guardian | Enforce telemetry minimization | Proposed telemetry fields and consent state | Redacted/denied telemetry payload |

## Decision pipeline

1. Collect bounded measurements from the existing probe and tunnel-health paths.
2. Validate freshness, range, sample count and network identity.
3. Build a feature vector without payloads, domains, IP history or credentials.
4. Produce candidate scores and confidence; unknown metrics remain unknown, not fabricated.
5. Apply hard policy gates: supported engine, verified capability, DNS-through-tunnel, kill-switch invariants, user exclusions, battery/data budget.
6. Execute only an allowed bounded action through the existing service API.
7. Verify that the selected route carries test payload and remains healthy.
8. Roll back to the last known-good route on failed verification.
9. Store a small local outcome record for future learning.

## Expanded capabilities

### 1. Context-aware Smart Connect
- Separate learned profiles by network class and time bucket, using a rotating local network key rather than storing SSIDs or BSSIDs in clear text.
- Use sample-count-aware ranking and confidence intervals; avoid overreacting to one fast ping.
- Consider p50/p95 RTT, jitter, packet loss, handshake success, verified payload, route stability and recovery time.
- Add hysteresis and a minimum improvement threshold to prevent route flapping.
- Keep the existing minimum-trial and time-budget constraints.

### 2. Adaptive protocol recommendation
- Rank only transports marked connectable by the runtime capability registry.
- Learn from actual tunnel success and verified payload, not parser success or profile presence.
- Respect user-selected protocol locks and exclusions.
- Never silently downgrade security or bypass certificate validation.

### 3. Gaming and real-time mode
- Use p95 latency, jitter, loss, stability and recovery time as route-quality signals.
- Add optional game-specific endpoint probes only when the endpoint is explicitly configured and the probe is technically valid.
- Clearly label generic route latency separately from in-game latency.
- Add user modes: Competitive (latency), Stable (loss/jitter), and Balanced.
- Do not promise lower game ping when game-server geography or telemetry is unknown.

### 4. Self-healing and predictive recovery
- Detect handshake success without payload, stalled traffic, repeated reconnects, DNS failure and network handover.
- Use bounded retry budgets, cooldowns and exponential backoff.
- Re-probe after Wi-Fi/mobile handover and avoid attributing samples to the wrong network.
- Verify recovery before showing “connected”; preserve kill-switch behavior during recovery.

### 5. Security and privacy intelligence
- Enforce DNS through the tunnel, IPv4/IPv6 leak protections, kill-switch and app split-tunnel policy.
- Validate certificate, package signature, update digest and configuration provenance.
- Keep credentials and private keys outside agent inputs and logs.
- Telemetry stays opt-in and off by default; aggregate fields only, with retention limits and a visible delete control.
- Do not send browsing history, DNS names, destination IPs or packet contents to remote AI.

### 6. Diagnostics and support
- Produce a local health score with separate transport, DNS, route, security and stability components.
- Explain the top reason for a failure and the next safe action.
- Export a redacted support bundle only after explicit user action.
- Never expose secrets, tokens, private keys or full destination history.

### 7. Battery, data and accessibility
- Reduce active probing in battery saver, thermal stress and metered networks.
- Offer a low-data mode and a user-controlled scan schedule.
- Keep all core VPN functions operational without network access to AI services.
- Localize agent explanations and status labels with the app's supported language system.

## Model strategy

Start with deterministic scoring plus a lightweight contextual bandit/local statistical learner. This is more reliable and cheaper for route selection than a remote generative model. A language model may optionally summarize sanitized diagnostics or answer help questions, but it must not choose packet routes, handle secrets or control the VPN service directly.

## Safety invariants (must be automated tests)

- AI unavailable => VPN connect/disconnect and packet forwarding remain functional.
- No agent can enable a protocol whose runtime capability is false.
- No agent can disable kill switch, DNS protection or certificate validation.
- DNS remains inside the selected VPN policy; no direct-DNS fallback is introduced by an agent.
- No route switch is reported successful before verified payload.
- Failed candidate => bounded rollback to last known-good route.
- Stale, insufficient or invalid metrics cannot win route ranking.
- No route flapping under oscillating measurements.
- No telemetry is sent without explicit consent.
- Logs and support bundles contain no credentials, private keys, DNS query names or packet payloads.
- User protocol locks, split-tunnel choices and country preferences are authoritative.

## Implementation phases

### Phase A — foundation
- Add typed agent contracts, capability registry adapter and deterministic policy gate.
- Add unit tests for invalid/stale metrics, unsupported transports and security invariants.
- Add structured, redacted decision events.
- Keep the existing Smart Connect path as fallback.

### Phase B — intelligence
- Integrate confidence-aware route scoring with existing Smart Connect and GamingQuality.
- Add per-network local learning, hysteresis and recovery-time measurement.
- Add protocol recommendations using verified runtime capability flags.

### Phase C — resilience and security
- Add network-handover diagnosis, bounded self-healing, DNS/IPv6/kill-switch invariant tests.
- Add privacy guardian and opt-in aggregate telemetry controls.

### Phase D — user experience
- Add an AI status panel showing current mode, selected route, confidence, reasons, last recovery and privacy status.
- Add simple/advanced explanations and localized strings.
- Add support bundle preview and explicit export.

### Phase E — validation
- JVM/Robolectric tests, Rust unit tests and API 35/36 IPv4/IPv6 packet regressions.
- Instrumented tests for Wi-Fi/mobile handover, kill switch, DNS and route rollback.
- Real-device validation on Samsung Galaxy A55 / Android 17 before release claims.
- Benchmark battery, data use, connection time and route stability against current Smart Connect.

## Release gates

Do not enable automatic agent actions by default until Phases A-C pass. Initially ship recommendations and diagnostics in shadow mode; compare decisions with existing Smart Connect. Enable bounded automatic recovery only after regression and real-device tests pass. No new release is considered verified until signed APK continuity, updater integrity and release artifact checks pass.
