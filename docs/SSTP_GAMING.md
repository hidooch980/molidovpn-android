# SSTP transport and gaming fallback

## Status: profile metadata only — not connectable

Molido now has a strict SSTP gateway parser, gaming-path scoring helper and regression tests. It does **not**
yet contain an SSTP tunnel engine or PPP backend. Consequently SSTP is deliberately
not registered in Smart Connect, is not shown as a working protocol, and cannot
be selected as a live gaming fallback. The pure `GamingFallbackPolicy` is a tested
selection-policy component only; it is not wired to live probes or route switching. The capability gate in
`SstpTransport` must remain closed until both components are packaged.

## Intended gaming policy

1. Prefer WireGuard over a measured UDP path.
2. Measure candidate RTT distribution, jitter, packet loss and route stability;
   `GamingFallbackPolicy` rejects missing/invalid metrics and requires verified
   payload. Its inputs must come from a real tunnel path probe; generic internet
   probes are not in-game ping.
3. Consider SSTP only when UDP paths are blocked/unusable and a user-configured
   SSTP gateway passes TLS certificate validation and an authenticated tunnel
   transfer test.
4. Do not silently disable certificate validation, bypass the VPN, or route game
   traffic outside the tunnel.
5. Disable multi-hop by default for gaming; only switch a running match route
   after sustained degradation and a confirmed healthy replacement.
6. SSTP is TCP-based (normally TCP/443). TCP retransmission and head-of-line
   blocking can increase jitter under loss; port 443 does not make it inherently
   faster or lower latency.

## Required implementation before enabling

- Pin and review a maintained SSTP client implementation and its license.
- Build reproducible Android ARM64 and ARMv7 binaries/libraries; verify checksums.
- Integrate TLS with strict hostname and CA validation; no trust-all callbacks.
- Implement SSTP control/data framing, PPP LCP, authentication, IPCP/IPv6CP,
  DNS/routes and MTU handling through Android `VpnService`.
- Protect the upstream socket from VPN recursion and coordinate single-TUN ownership.
- Keep credentials in Android Keystore-backed encrypted storage; redact all logs.
- Add cancellation, reconnect, network handover, kill-switch and fail-closed behavior.
- Add emulator/unit CI plus authorized on-device authenticated traffic tests.
- Verify IPv4, IPv6, DNS, UDP and TCP leak behavior, disconnect/reconnect, Wi-Fi/mobile
  handover and actual game-region measurements before advertising availability.

No credentials, free public gateways, or unverified server addresses are bundled.
A TCP/443 reachability probe alone is never evidence of a working SSTP tunnel.
