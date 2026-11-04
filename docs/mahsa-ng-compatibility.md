# MahsaNG compatibility and server-source policy

Status: share-link parser fixtures added; CI/device results are pending. This is not a claim that MahsaNG servers have been connected or benchmarked.

## What was reviewed

The upstream MahsaNG project describes itself as a V2RayNG-derived client for community-donated configurations. Its published README identifies DNS-over-HTTPS, TLS fragmentation, fake HTTP/WebSocket host handling and rotating configurations. It also states that authentication with the Mahsa server and parts of donated configuration handling are closed-source. See https://github.com/GFW-knocker/MahsaNG and https://github.com/GFW-knocker/MahsaNG/releases.

## Current Molido compatibility

Molido's existing V2rayNodes parser is the compatibility boundary for standard share links. The new MahsaNgCompatibilityTest uses synthetic fixtures only and checks parsing of VLESS + Reality + Vision over TCP; VLESS WebSocket + TLS; Trojan WebSocket + TLS; VMess WebSocket + TLS; and Shadowsocks AEAD. It also verifies that malformed links and unknown/proprietary schemes fail closed. These tests validate import/render compatibility, not endpoint availability, successful authentication, throughput or censorship resistance.

## Not currently supported by the standard-link adapter

- MahsaNG's authenticated/private subscription protocol and private server API
- Psiphon-specific links or CDN-fronting flows
- Mahsa Xray-Core-specific WireGuard Noise extensions
- proprietary rotating-config and random-subdomain orchestration

Do not reverse engineer, scrape, replay or impersonate the private Mahsa server authentication protocol. Do not commit subscription URLs, user credentials, donated private configurations, tokens, or live server lists to Git.

## Server reuse policy

MahsaNG-originated nodes may be considered for a separate, opt-in compatibility pool only when the configuration is explicitly exported/shared by its owner or published for reuse under clear permission. Before promotion: redact credentials from logs and telemetry; keep secrets local and encrypted; validate the protocol/config with the pinned engine without weakening TLS; test a real authenticated tunnel and HTTPS transfer on the target device/network; record latency, jitter, loss, throughput, stability, test age and network class; re-test on the user's current network; apply health scoring, cooldowns, expiry and immediate removal of revoked/bad nodes.

A parsed share link, reachable TCP port, public repository or successful TLS handshake alone is not evidence that a server is usable or authorized for reuse. MahsaNG's servers must remain isolated from Molido's production pool until the permission and end-to-end test gates pass.

## Next validation

Run the Android release unit-test suite on the development branch and inspect the CI report. Then perform side-by-side device tests using the same explicitly authorized configuration on MahsaNG and Molido: connection success, DNS/IP leak, IPv6, HTTPS transfer, latency/jitter/loss, throughput, reconnect and battery impact.
