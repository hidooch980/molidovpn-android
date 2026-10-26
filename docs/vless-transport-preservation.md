# Preserve VLESS XHTTP and WebSocket profiles

This change targets imported VLESS share links, including subscriptions, My
configs and QR import. It uses the existing Xray engine, recorded as 26.10.21
in `.github/core-versions.json`; it adds no Android dependency or protocol engine.

## Conditions applied

- Keep the supplied HTTP Host, TLS SNI, path/query, ALPN and supported fingerprint.
  Host and SNI can differ; they are not globally rewritten to the dial address.
- Do not rotate the fingerprint or inject WS early data after failures for
  imported VLESS XHTTP/WS. Preserve publisher-provided `?ed=` parameters. Other
  transport retry behavior remains unchanged.
- Normalize URI aliases `splithttp` → `xhttp` and `websocket` → `ws`.
- Use XHTTP's default `auto` only if mode is omitted. Reject unsupported explicit
  modes instead of silently replacing them with `auto`.
- Preserve the entire valid XHTTP `extra` object, including XMUX, padding,
  custom headers and separate `downloadSettings`. Malformed/non-object/trailing
  JSON is rejected instead of discarded. Input is limited to 65,536 characters after URI
  decoding. Unknown fields are preserved for the engine to interpret.
- Reject XHTTP `headers.Host` (case insensitive), non-string header values,
  malformed downstream objects, and `stream-one` combined with downstream
  settings. These are preflight checks, not a complete Xray schema validator.
- Preserve fingerprints supported by the recorded engine, including `unsafe`,
  `randomizednoalpn` and named ClientHello profiles. Unsupported fingerprints on
  VLESS XHTTP/WS reject the link rather than silently falling back. REALITY
  rejects `unsafe` and `hellogolang`, as the engine does.
- Keep existing checks disallowing Vision flow on WS/XHTTP and REALITY on WS.
  Keep certificate verification enabled.
- Retain ALPN: XHTTP H3 uses UDP in Xray. Do not force H3 or H2 globally on every
  operator. Likewise do not force fragment/MTU/early-data values onto all profiles.
- Keep existing CDN restrictions: XHTTP is not automatically converted into a
  random edge-IP connection. Existing WS expansion requires TLS, a Host, a
  supported CDN port, and a known Cloudflare address at the ShardEdges layer.

The importer still supports VLESS `encryption=none` only. Arbitrary JSON Xray
profiles, newer VLESS Encryption strings and every engine-specific XHTTP extra
field are not newly implemented or fully validated by this change.

## Verification

`XhttpProfileTest` checks strict modes/extra and documented incompatibilities.
`VlessTransportTest` checks the real importer/renderer, fixed retries at multiple
failure counts, aliases, TLS/Host/SNI/path/ALPN, downstream preservation,
fingerprints, incompatible combinations and unchanged retries for other transports.

The existing profile CI runs the full Android unit suite. With `MOLIDO_XRAY` set,
the integration test passes actual rendered outbounds to `xray run -test`.
CI fetches the SHA-256-verified Linux build of Xray 26.10.21 and requires its
version to match the recorded Android core; a core update requires updating this
test pin. The validator opens no VPN connection. Neither passing config tests
nor emulator packet tests prove stability on MCI, Samantel or Irancell.

## Primary references

- [Recorded Xray TLS/REALITY validation](https://github.com/XTLS/Xray-core/blob/v26.10.21/infra/conf/transport_security.go)
- [XHTTP mode/extra/header/downstream checks](https://github.com/XTLS/Xray-core/blob/v26.10.21/infra/conf/transport_method.go)
- [Supported fingerprints](https://github.com/XTLS/Xray-core/blob/v26.10.21/transport/internet/tls/tls.go)
- [WebSocket path, Host and early-data behavior](https://github.com/XTLS/Xray-docs-next/blob/main/docs/en/config/transports/websocket.md)
- [Official XHTTP discussion](https://github.com/XTLS/Xray-core/discussions/4113)
