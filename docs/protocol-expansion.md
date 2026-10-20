# OpenVPN and OpenConnect expansion — PHASE 0

Status: design only. Neither protocol is implemented or selectable in the current APK.
Approved scope: OpenVPN TCP/UDP, AnyConnect-compatible OpenConnect, and promotion of
servers that actually work on the user's current Iranian network.
Baseline inspected: Android main 0a5c15a53cb94e024f4f62b35e914c90290a96d9.

## Existing integration points

- methods/MethodRegistry.kt advertises methods to Smart Connect. Keep both new
  methods absent until their packaged engines and configuration are usable.
- methods/TunnelMethod.kt provides pre-probes, not tunnel ownership.
- MolidoVpnService.kt owns the Android VPN interface, application exclusions,
  Iranian route bypass, socket protection, lifecycle and cancellation.
- engine/SmartScore.kt already scores stability, latency and measured throughput.
- ShardManager.kt handles the existing proxy-based engines. Its SOCKS route cannot
  be assumed to work with a direct IP tunnel engine.
- app/build.gradle.kts currently packages ARM64 and ARMv7, minSdk 26, targetSdk 36.
  Do not silently change device compatibility.

## PHASE 1 — OpenVPN

Implement a reproducible, pinned native engine build for both packaged ABIs.
Choose the engine distribution and verify its source/license integration before
vendoring; do not treat the entire ics-openvpn app as a drop-in Maven library.

Import .ovpn through Android's document picker. Parse the supported configuration
with the selected engine's parser, preserve remote and transport semantics, and
show unsupported directives as errors. Never silently discard security options.
Support TCP and UDP; use available profile ports without rewriting them to 443.

Public profiles are untrusted input: do not execute scripts/plugins, follow
arbitrary referenced local files, disable certificate verification, or record
private keys/passwords in logs. Ask the user to resolve required external
certificates through document selection. Store credentials separately using the
app's encrypted storage; exclude secrets from backup and shared diagnostics.

Integrate engine callbacks for authentication, certificate verification, routes,
DNS, counters, reconnect and stop into the existing service. Protect every
upstream socket from re-entering the VPN. Apply the user's application selection
and Iranian bypass rules consistently, including IPv6 and DNS handling.

Acceptance gate:
- Unit tests for valid, malformed and unsupported imports; redacted diagnostics.
- CI native builds and Android APK builds for ARM64/ARMv7.
- On-device authenticated TCP and UDP sessions; real HTTPS data transfer through
  the tunnel, disconnect/cancel/reconnect, network handover and route/DNS tests.
- Verify empty include-list remains an actionable error rather than changing the
  user's routing policy.
- No automatic production release until this gate passes.

## PHASE 2 — OpenConnect / AnyConnect

Build pinned libopenconnect and its dependencies for both ABIs. Use its supported
library/JNI interface; a shell command alone cannot configure Android VpnService.

Support HTTPS gateways, authentication groups and interactive authentication.
Keep password, cookie and MFA flows local. An automatic scan must not repeatedly
submit credentials or MFA challenges; distinguish authentication-required from
network-failed. Verify server certificates and offer an explicit, per-profile
trust decision where necessary; never install a global accept-all callback.

Connect the native engine's TUN setup, socket protection, DNS/routes and lifecycle
to the service. Attempt DTLS where supported and retain the engine's TLS fallback.
An authentication success is not sufficient evidence that internet access works.

Acceptance gate: Phase 1 lifecycle/routing/security tests, plus bad-certificate
rejection, expired credentials, authentication-group/MFA cancellation, and DTLS
unavailable with TLS fallback. Use an authorized test server/account; no production
credentials in CI, repository files or reports.

## PHASE 3 — Servers that work on the current network

Discover candidates separately from connecting. A reachable TCP port is only a
preliminary signal. Promote to "working" only after an authenticated tunnel and
validated HTTPS transfer through that tunnel succeed.

Store local evidence by profile identity (including configuration revision),
protocol/transport, and current network identity. Do not transfer a good MCI
result to Samantel or a different Wi-Fi network. Do not request location/SSID
permission solely for scoring; where network identity is unavailable, treat
evidence as session-local or unknown.

Track last success, latency samples, throughput, failures and test age.
Prefer recent stable transfer evidence over low TCP handshake time. New servers
remain untested until measured. Use expiring failure cooldowns and bounded retries.
Unknown throughput is not zero throughput. Existing SmartScore is the scoring
starting point, not proof that an imported server works.

Android permits only one active VPN service for this app: background TCP probes
can be parallel, but full OpenVPN/OpenConnect tunnel trials must be coordinated.
Do not interrupt working video/calls every five seconds to test another tunnel.
Prepare candidates in the background and switch only on explicit user action or
confirmed degradation. Never report a handover as guaranteed interruption-free.

The requested five-second catalog check is distinct from full connection tests.
Keep refreshes single-flight, use HTTP validators, back off failures, preserve
usable cache and keep the active session's configuration stable during refresh.
Downloading new executable engines from an unsigned catalog is out of scope.

## Candidate sources

- VPN Gate: https://www.vpngate.net/en/ — OpenVPN profile discovery only.
- VPNBook: https://www.vpnbook.com/freevpn/openvpn — public OpenVPN profiles.
- OpenConnect: user-supplied or explicitly authorized provider gateways/accounts.
  No authenticated free AnyConnect pool has been verified.

No source is marked as working in Iran until the user's device measures it.
Do not publish shared provider credentials or scrape private accounts.

## Upstream references reviewed on 2026-10-20

- https://github.com/schwabe/ics-openvpn — Android integration and upstream scope.
- https://github.com/schwabe/ics-openvpn/blob/master/doc/LICENSE.txt
- https://github.com/openconnect/openconnect — official repository mirror.
- https://github.com/openconnect/openconnect/blob/master/java/src/org/infradead/libopenconnect/LibOpenConnect.java

## Phase lock and current evidence

PHASE 0: source integration review and acceptance plan prepared.
PHASES 1–3: not implemented; native dependency selection/build and on-device
authenticated tests remain outstanding. No new protocol or server was enabled.
This documentation-only change needs no Android test run and must not trigger an
APK publication. It is not evidence that the earlier media build succeeded.
