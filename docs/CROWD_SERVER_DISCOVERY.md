# Molido Community Server Discovery

## Goal

Build a provider-independent, community-fed directory of VPN endpoints. A client may submit a small health observation after a connection attempt; accepted observations help rank and refresh endpoint metadata for other clients.

This document defines the safe ingestion contract. It does not claim that client reporting or a public ingestion service is implemented yet.

## Data flow

1. A client discovers an endpoint from a provider, user import, or a public directory.
2. With an explicit opt-in, the client submits a signed, rate-limited observation to a dedicated Molido ingestion API (not a GitHub token and not a GitHub Actions workflow).
3. The ingestion service validates schema, protocol, hostname/IP syntax, timestamps, replay window, report quotas, and endpoint provenance.
4. Independent probes and corroborating reports update a confidence score.
5. A curator/publisher emits a versioned, signed public directory snapshot.
6. Android/iOS clients fetch the snapshot from a CDN or GitHub mirror, verify its signature and schema, and retain the last-known-good copy.

## Strict privacy boundary

Never submit or store:
- VPN usernames, passwords, private keys, certificates, subscription URLs, or full imported profiles;
- browsing destinations, DNS query names, packet contents, message contents, or application activity;
- stable device identifiers, account identifiers, precise location, or raw IP addresses of reporting clients.

Reports should use rotating anonymous installation tokens or privacy-preserving rate-limit tokens. The service should discard transport-level client IP data as early as operationally possible and never publish it. Do not derive or publish a user's location from their network.

## Observation schema (v1)

Allowed fields:
- `schema_version`: integer, currently 1;
- `endpoint_id`: stable hash of normalized endpoint + protocol + port (not a user identifier);
- `protocol`: allowlisted protocol enum;
- `endpoint_host`: validated hostname or public IP of the VPN server;
- `port`: protocol-valid integer;
- `result`: connected, timeout, refused, tls_error, auth_rejected, or unknown;
- `rtt_ms`, `loss_percent`, `observed_at`: bounded measurements;
- `source_class`: provider_directory, user_import, or community;
- `client_version`: coarse app version only.

Never include credentials or arbitrary free-form strings. For IKEv2, a successful TCP reachability check alone must not be represented as a successful IKE negotiation.

## Trust and anti-abuse rules

- Treat every client report as untrusted input; one report never makes an endpoint trusted or publicly recommended.
- Require endpoint syntax/protocol validation and provenance checks before accepting it.
- Use per-token quotas, endpoint-level rate limits, replay protection, duplicate suppression, and abuse monitoring.
- Use independent server-side probes where lawful and technically appropriate; do not probe private, loopback, link-local, multicast, or reserved address ranges.
- Require multiple independent observations over a rolling window before promoting an endpoint.
- Separate reachability, handshake success, authentication success, and user-reported quality; never collapse them into one vague “online” flag.
- Expire stale observations and automatically demote endpoints after repeated failures.
- Keep a quarantine state for suspicious, malformed, or rapidly changing endpoints.
- Sign directory snapshots; clients reject unsigned, expired, rollback, or invalid-schema snapshots.

## Publication policy

Publish only endpoint metadata that passed validation and promotion thresholds. Include protocol, host, port, IKE identity where appropriate, provenance class, observation window, confidence tier, and expiry. Do not publish reporter counts if they could expose a small cohort; use broad confidence tiers instead.

GitHub Actions may periodically mirror an already-curated signed snapshot. It must not be the per-client ingestion endpoint: GitHub Actions is scheduled in minutes, GitHub APIs are rate-limited, and putting write credentials in an app would expose the repository.

## Rollout stages

1. Implement local observation collection with explicit opt-in and no network submission.
2. Add a dedicated ingestion API and abuse controls; test with synthetic reports.
3. Add signed snapshot generation and client signature verification.
4. Add client upload with bounded retry/backoff and privacy controls.
5. Run a closed beta, review false reports and abuse, then enable public publication.

## Verification requirements

Before public rollout, test schema fuzzing, replay attempts, quota bypass, malicious endpoint injection, signature tampering, rollback attacks, stale cache behavior, and privacy payload inspection. Real protocol handshake and independent DNS/IP leak tests remain separate device-level validations.
