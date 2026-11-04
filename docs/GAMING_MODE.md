# Molido Gaming Mode — implementation plan and safety contract

## Goal

Rank available routes for interactive games using measured latency distribution, jitter, packet loss, stability and recovery time. The score is a route-selection aid, not a promise of lower in-game ping: probe destinations and game-server geography must be representative.

## Current code in this change

- `GamingQuality` is a deterministic scoring component with unit tests and is now used by Android Smart Connect when **Secure gaming route scoring** is enabled in Settings.
- Smart Connect adapts its existing ordered candidate probe window into gaming observations and ranks candidates using nearest-rank p95 RTT, median absolute consecutive RTT variation, packet-loss percentage and success ratio.
- Gaming and normal Smart Connect scores use separate remembered-winner and learning-bandit keys; enabling gaming scoring also disables the remembered-winner fast path so every candidate receives a fresh trial.
- Missing optional metrics are neutral; an empty/all-failed probe window scores zero. Recovery time is not currently measured and remains neutral.
- Current probes are generic route reachability/latency probes, not game-server-specific probes. There is no game detection, no in-game ping claim, and no continuous game-specific route switching yet.

## Existing Android DNS preset safeguard

Secure gaming route scoring does not enable direct Gaming DNS and does not change DNS routing. DNS stays inside the VPN tunnel by default. Selecting one of the legacy Iranian Gaming DNS presets requires an explicit confirmation. The confirmation explains that DNS requests are routed outside the VPN tunnel and may be visible to the local network or ISP. Consent is stored separately from the preset: a migrated or previously saved gaming preset without the consent bit falls back to the connection mode's tunnel DNS. On Android versions below 13, direct routing is unavailable and the service also falls back to tunnel DNS. Re-selecting a gaming preset and accepting the warning is required to enable the direct route. This is an explicit-risk option, not a leak-free mode.

## Recommended operating modes

1. **Secure Game (default):** retain full-tunnel routes, DNS inside the tunnel, IPv4/IPv6 leak protection and kill switch. Do not use `VpnService.Builder.excludeRoute` for game DNS destinations. The service fails closed to tunnel DNS unless a separate explicit-consent preference is present.
2. **Direct Gaming DNS (explicit opt-in):** legacy provider-specific resolvers may be routed outside the VPN only after the warning is accepted. The opt-in is separate from the selected preset and is supported only on Android 13+. Never label this mode leak-free.
3. **Game-aware route selection:** probe multiple region-appropriate targets, compare p95/jitter/loss over a rolling window, and avoid switching during a match unless the active path is persistently degraded.
4. **Background traffic control:** optional user-controlled download/update pause; never silently block essential system traffic.

## Proposed initial score

| Signal | Weight |
|---|---:|
| Packet loss | 30% |
| p95 RTT | 25% |
| Jitter | 20% |
| Probe success / stability | 15% |
| Recovery time | 10% |

Thresholds in code are initial ranking heuristics, not universal gaming SLAs. Calibrate by game, region, access network and real-device measurements before production use.

## Validation gates

- Unit tests for monotonic scoring and loss/jitter/tail-latency behavior.
- Android device tests on Wi-Fi and cellular, IPv4-only and dual-stack networks.
- Compare probes against the actual game region where lawful and technically possible.
- Verify DNS, IPv4, IPv6 and UDP behavior with the tunnel connected, reconnecting and stopped.
- Confirm no route switch during a stable match; verify bounded recovery after sustained degradation.
- Do not claim "zero leak" or publish a gaming-performance guarantee based on synthetic unit tests alone.
