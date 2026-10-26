# Hysteria2 share links: port hopping

MolidoVPN already bundles sing-box 1.12.25 for Hysteria2, TUIC and AnyTLS.
This change uses that existing engine; it adds no binary or dependency and does
not change the minimum Android version (API 26).

## User-visible behavior

Import a link through **My configs**, a QR code or an existing subscription:

```text
hy2://password@vpn.example:443,20000-30000/?sni=tls.example#MyVPN
hysteria2://password@[2001:db8::1]:20000-30000
hy2://password@vpn.example
```

Previously the importer silently discarded everything after the first comma,
rejected range-only addresses, and required an explicit port. It now preserves
the entire port list, translates every entry to sing-box's `start:end` syntax
(including `443:443` for a single port in a list), and
uses port 443 when no port is supplied. An explicitly empty port is invalid.
Single-port profiles still emit only `server_port`; hopping profiles emit only
`server_ports`. The engine's default hopping interval is retained. The first
port (or the start of the first range) is only metadata for the existing node
model; sing-box selects the actual port. TLS and obfuscation settings are
preserved. The existing sidecar handles probes and live traffic alike.

Invalid list entries reject the complete link, so an unusable outbound cannot
break the sidecar for the rest of a subscription. Parsing is bounded to 2,048
authority characters and 64 list entries. Ranges are not expanded in the app.

## Evidence and limits

- [Official Hysteria2 URI scheme](https://v2.hysteria.network/docs/developers/URI-Scheme/)
  defines multi-port authorities and default port 443.
- [sing-box 1.12.25 Hysteria2 configuration](https://github.com/SagerNet/sing-box/blob/v1.12.25/docs/configuration/outbound/hysteria2.md)
  defines `server_ports`, available since 1.11.0, and its conflict with `server_port`.
- [Pinned engine implementation](https://github.com/SagerNet/sing-box/blob/v1.12.25/protocol/hysteria2/outbound.go)
  passes `ServerPorts` to its Hysteria2 client.
- [Official port-hopping documentation](https://v2.hysteria.network/docs/advanced/Port-Hopping/)
  describes server-side port forwarding/listening requirements. Hopping does
  not overcome a network that blocks all UDP.

Only ports supplied by the profile are used. No server is provisioned or scanned.
This is not a claim of connectivity, higher throughput or stability on MCI,
Samantel or Irancell. Those require an actual Hysteria2 server configured for
all listed ports and tests on the relevant Android device/operator. Newer
sing-box 1.14-only fields and other engines are outside this change.

## Validation

`Hysteria2EndpointTest` covers IPv4/domain/bracketed IPv6, default and explicit
ports, ranges, deduplication, malformed entries and input bounds.
`Hysteria2ImportTest` covers the actual URI importer → node → sing-box outbound,
credentials/SNI/TLS preservation, mixed subscriptions, and TUIC/AnyTLS regression.
The existing profile-validation PR workflow compiles the Android sources and
runs the full unit-test suite. It also fetches the SHA-256-verified Linux build
of the same sing-box version and checks the actual rendered configurations with
`sing-box check` (no connection is opened). This contract test catches differences
between the documented format and what the pinned engine actually accepts.
Locally it is skipped unless `MOLIDO_SINGBOX` points to the engine binary.
A passing parser/config test is not a phone or
operator connectivity test.
