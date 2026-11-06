# Molido Agent Hub

Molido Agent Hub is a deterministic, local-code coordinator for built-in VPN skills.
It does not download executable code, run remote prompts, or send user traffic to an
AI provider. Every skill has a fixed owner, cadence and safety gate.

## Implemented skill ownership

| Skill | Current owner / behavior | Execution |
|---|---|---|
| Server discovery | Refresh V2Ray, SHARD and Smart Split catalogs | Automatic dispatch every 5 minutes while live sync is active |
| Network policy manager | Refresh edge, routing and resilience policy from trusted sources | 5-minute dispatch; provider cache/backoff remains authoritative |
| Remote app config manager | Fetch validated mode flags and admin announcements | 5-minute dispatch; provider cache/backoff remains authoritative |
| Connection optimizer | Smart Connect, SmartPlan, SmartScore and local network learning rank transports | On demand through existing connection flow |
| Network health scanner | BackgroundScanner / JobScheduler bounded node probes | Android scheduled background job; not a guaranteed exact hourly timer |
| Privacy guard | Local-only learning and bounded anonymous network-health reporting | Enforced by existing privacy/reporting code |

These six entries describe existing ownership and integration points. They are not six
independent autonomous LLM agents. The larger ten-role design (routing, protocol advice,
gaming, recovery, diagnostics, learning, battery/data and coordinator roles) remains a
roadmap and must only be marked implemented when each role has executable code, tests,
and a real integration point.

## Agent health insights

- `AgentHealth.summarize` converts persisted runtime snapshots into deterministic
  `HEALTHY`, `DEGRADED`, `ERROR`, `NOT_RUN`, `DISABLED` and `CLOCK_SKEW` states.
- A newer provider exception overrides an older successful call; only the exception class
  is exposed, never exception messages, credentials or fetched payloads.
- A recent provider return is a heartbeat only. It does not claim that a remote catalog
  changed or that data was applied.
- The default stale threshold is 30 minutes and is configurable by the caller.
- This is a tested diagnostics model; a dedicated in-app dashboard still requires UI wiring.

## Dispatch and observability rules

- The automatic coordinator dispatches only skills explicitly marked AUTOMATIC.
- A pure policy function returns stable block reasons: `not_automatic`, `invalid_interval`,
  `clock_rollback`, `not_due`, `offline`, `metered_network`, `battery_low`,
  and `tunnel_active`.
- Persisted wall-clock checkpoints are guarded against clock rollback, so a device clock
  correction cannot cause an early retry storm.
- A dispatch checkpoint means the skill was attempted, not that a provider successfully
  downloaded or applied data. Provider logs and validated cache contents remain the
  source of truth for success.
- Android may delay scheduled background work; a five-minute cadence applies to active
  live sync, not guaranteed background execution.

## Safety

- Network-dependent skills do not run while offline.
- Existing provider-level ETag, last-known-good cache, validation and retry controls
  remain authoritative.
- No server-supplied content is executed as code.
- The VPN tunnel and packet engine do not depend on the agent coordinator being enabled
  or healthy. Agent failures must never be allowed to tear down an active tunnel.
- Network probes remain bounded and must not scan through a full VPN tunnel.

## Self-healing and maintenance agent

- Watches completion of Android validation, native IPv4/IPv6 regression and server-catalog guard workflows.
- Classifies CI failures into transient, deterministic and unclassified groups.
- Retries a transient failed run once only; repeated or deterministic failures create a deduplicated diagnostic issue.
- Redacts common authorization/token values and includes only a bounded error excerpt in diagnostic issues.
- Runs weekly Ruff lint/format fixes only on the agent-owned Python diagnostic files. Changes are proposed as a pull request and must pass tests/review before merge.
- Dependabot proposes weekly Gradle and GitHub Actions dependency updates.
- The agent never edits release signing keys, credentials, VPN security policy, user data, or application source automatically. It does not auto-merge its own fixes.

## Agent as the backup and recovery supervisor

The Agent is the **support plane**, not a replacement packet engine. The VPN service remains the sole authority for tunnel lifecycle and traffic routing.

- **Primary recovery owner:** `MolidoVpnService` owns the live-tunnel watchdog, bounded SHARD node rotation, automatic reconnect backoff, connectivity-restoration callback and kill-switch sealing. A user-requested disconnect always cancels recovery.
- **Agent backup role:** observe privacy-safe service/skill health, classify degraded or stale state, preserve a bounded diagnostic reason, and surface recovery status. It must not start a second tunnel, race the service's reconnect scheduler, or alter the selected profile while a recovery attempt is in flight.
- **Fallback order:** keep the existing verified tunnel if healthy; let the service rotate a failed node where supported; let its reconnect path retry the stored, validated configuration; keep the kill switch sealed while a VPN-mode tunnel is down. Never fall back to direct/unencrypted traffic to make the status appear online.
- **Escalation:** if the service recovery budget is exhausted or its scheduler is unavailable, the Agent may report the failure and offer a user-confirmed retry. It must not bypass Android VPN consent, kill-switch policy, certificate checks, profile validation or protocol capability gates.
- **Failure isolation:** disabling or crashing the Agent must not stop an active tunnel or prevent the service watchdog from recovering it. Agent network work is bounded, serialized and separate from the packet data plane.
- **Verification required before calling this fully operational:** tests for user-stop cancellation, reconnect backoff, network-restoration retry, kill-switch sealing, stale Agent health, duplicate recovery suppression and service/Agent process races, followed by physical-device network-switch and soak tests.

The CI self-heal workflow is a separate backup for repository health: it retries a classified transient workflow failure once and opens a deduplicated diagnostic issue for deterministic/repeated failures. Its `skipped` result on a successful build is expected; it only runs recovery after a failed watched workflow.
