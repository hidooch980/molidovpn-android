# MolidoVPN Quality and Agent Charter

Status: proposed engineering contract for the next release cycle.

This charter governs Android, Windows, shared client behavior, release automation, and the on-device Agent. It is a set of acceptance rules, not a claim that every capability is already implemented.

## The five final principles

1. **Security before speed.** Never route traffic directly or without encryption as a recovery shortcut. Preserve Android VPN consent, profile validation, certificate checks, DNS/IP leak protections, IPv4/IPv6 policy, and the kill switch. Unknown or unverified paths fail closed.
2. **One tunnel authority; Agent is the supervisor.** `MolidoVpnService` remains the only owner of tunnel lifecycle and packet routing. Agent observes service health, diagnoses, deduplicates and reports recovery state; it delegates reconnect/node rotation to the existing service. Agent failure must never stop a healthy tunnel or its watchdog.
3. **Evidence before claims.** A protocol, server, AI feature, platform build, or recovery path is advertised as supported only after implementation, automated tests, and the relevant integration/device test. A provider heartbeat is not proof of fresh data; a successful compile is not proof of a working tunnel.
4. **Safe, reversible, reviewed changes.** Work on branches and pull requests, preserve last-known-good configuration, bound retries and background work, redact diagnostics, avoid secrets/user payloads, and never let automation self-approve or auto-merge its own source changes.
5. **Reliability and user control.** Respect explicit disconnect, VPN consent, battery/data constraints and user-selected modes. Recovery must be bounded, observable and cancellable. Show truthful status, explain degraded states, and offer user-confirmed escalation instead of infinite retries.

## Product capabilities to build

- Guardian monitor: read-only observation of service/tunnel state, network availability, DNS reachability, last recovery result and Agent heartbeat.
- Recovery coordinator: a single-flight, idempotent request path into existing service recovery; explicit user-stop latch; bounded retry budget; cooldown; stale-request rejection.
- Smart server ranking: use measured latency, jitter, packet loss, handshake/connect success, stability window and recent failures. Do not invent scores when measurements are absent.
- Privacy and leak guard: test DNS and IPv4/IPv6 behavior without exposing payloads; preserve kill switch during tunnel loss; never use direct fallback to fake connectivity.
- Home status: connection, public IP only when verified through the tunnel, server/protocol, ping and Agent state; manual/automatic/gaming modes remain user-controlled.
- Diagnostics: bounded local event history with reason codes, timestamps and exception classes only; no credentials, profile contents, raw URLs or traffic payloads.
- Resilience: network handover handling, process restart behavior, battery-aware scheduling, last-known-good configuration and safe rollback.
- Cross-platform parity: feature availability is labeled per platform and protocol; Android-only VPN APIs must not be implied to exist on Windows/iOS/PWA.

## Delivery sequence and acceptance gates

### Gate 0 — Baseline and triage
Inventory open PRs/issues and workflows in both client repositories. Reproduce failures, classify duplicates vs real defects, record baseline build/test results, and do not close unresolved reports without evidence.

### Gate 1 — Release pipeline integrity
Fix path-filtered workflow gates without weakening required checks. Verify required Android validation, optional suites when applicable, artifact provenance, package IDs, signing continuity, version monotonicity, checksums and release notes. No release while required jobs are pending or failed.

### Gate 2 — Agent supervisor implementation
Inspect actual service APIs and lifecycle before wiring. Add a narrow service-owned recovery request interface; Agent must not construct a second VPN service or manipulate packet routing. Add unit tests for duplicate suppression, cooldown, retry budget, explicit user disconnect, stale state, Agent disabled/crashed, service scheduler unavailable and process races.

### Gate 3 — Observability and home UI
Add a truthful Agent status surface (healthy/degraded/error/disabled/not yet run), service recovery state, verified public IP and diagnostics entry. UI must not claim "connected" based only on an Agent heartbeat.

### Gate 4 — Network and privacy validation
Automate and, where required, run physical-device tests for Wi-Fi/mobile handover, offline/online transitions, IPv4, IPv6, DNS, kill-switch behavior, reconnect cancellation, battery and memory. Keep test evidence attached to the PR/release.

### Gate 5 — Release candidate and rollout
Build unsigned validation artifacts first; run tests and inspect reports/screenshots; verify signing identity against the prior official release; create checksums; publish a release candidate; then publish stable only when every required gate is green. Keep rollback instructions and changelog.

## Release-blocking conditions

- Any direct/unencrypted fallback while VPN mode is expected.
- User disconnect does not cancel recovery.
- Agent can start a competing tunnel or bypass Android consent.
- Kill switch, DNS/IP/IPv6 protection regression.
- Duplicate/unbounded retries or recovery storm.
- Required CI failure, signing mismatch, missing checksum, or unverified artifact.
- A feature is represented as supported without implementation and evidence.

## Status vocabulary

- **Implemented:** source code exists.
- **Automated-tested:** relevant automated tests pass.
- **Integration-tested:** real component integration passes.
- **Device-tested:** validated on a physical supported device/network.
- **Released:** signed artifact is published with checksums and release notes.

Never collapse these states into a single "done" label.
