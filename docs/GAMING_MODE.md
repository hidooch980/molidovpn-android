# Molido Gaming Mode — implementation plan and safety contract

## Goal

Rank available routes for interactive games using measured latency distribution, jitter, packet loss, stability and recovery time. The score is a route-selection aid, not a promise of lower in-game ping: probe destinations and game-server geography must be representative.

## Current code in this change

- `GamingQuality` is a pure deterministic scoring component with unit tests.
- It uses nearest-rank p95 RTT, median absolute consecutive RTT variation, packet-loss percentage, success ratio and measured recovery time.
- Missing optional metrics are neutral; an empty/all-failed probe window scores zero.
- This component does not yet collect probes, change routes, identify games or expose a UI. Those require separate integration and device tests.

## Recommended operating modes

1. **Secure Game (default):** retain full-tunnel routes, DNS inside the tunnel, IPv4/IPv6 leak protection and kill switch. Do not use `VpnService.Builder.excludeRoute` for game DNS destinations.
2. **Direct Gaming DNS (explicit opt-in):** legacy provider-specific resolvers may be routed outside the VPN only after a clear warning that DNS traffic bypasses the tunnel. Never label this mode leak-free.
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
