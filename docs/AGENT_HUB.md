# Molido Agent Hub

Molido Agent Hub is a deterministic, local-code coordinator for built-in VPN skills.
It does not download executable code, run remote prompts, or send user traffic to an
AI provider. Every skill has a fixed owner, cadence and safety gate.

## Automatic skills

| Skill | Responsibility | Cadence |
|---|---|---:|
| Server discovery | Refresh V2Ray, SHARD and Smart Split catalogs | 5 minutes while live sync is active |
| Network policy manager | Refresh edge, routing and resilience policy | 5-minute dispatch; each provider keeps its own backoff |
| Remote app config manager | Fetch validated mode flags and admin announcements | 5-minute dispatch; provider cache/backoff applies |

## Delegated skills

- **Connection optimizer** is owned by Smart Connect, SmartPlan, SmartScore and
  local network learning. It selects transports using measured connection quality.
- **Network health scanner** remains owned by BackgroundScanner/JobScheduler. It
  requires a network, avoids metered connections, waits for battery-not-low and
  does not probe through a full VPN tunnel.
- **Privacy guard** is enforced by local-only learning and existing anonymous,
  bounded network-health reporting.

## Safety

- Network-dependent skills do not run while offline.
- Every automatic skill has a persisted dispatch checkpoint to avoid restart storms.
- Existing provider-level ETag, last-known-good cache, validation and retry controls
  remain authoritative.
- A refresh dispatch is not reported as a successful download. Provider-level logs
  and validated cache contents remain the source of truth.
- No server-supplied content is executed as code.
- The Android operating system may delay background work; the five-minute cadence
  applies to active live sync. Background jobs remain subject to Android scheduling.

## Self-healing and maintenance agent

- Watches completion of Android validation, native IPv4/IPv6 regression and server-catalog guard workflows.
- Classifies CI failures into transient, deterministic and unclassified groups.
- Retries a transient failed run once only; repeated or deterministic failures create a deduplicated diagnostic issue.
- Redacts common authorization/token values and includes only a bounded error excerpt in diagnostic issues.
- Runs weekly Ruff lint/format fixes only on the agent-owned Python diagnostic files. Changes are proposed as a pull request and must pass tests/review before merge.
- Dependabot proposes weekly Gradle and GitHub Actions dependency updates.
- The agent never edits release signing keys, credentials, VPN security policy, user data, or application source automatically. It does not auto-merge its own fixes.
