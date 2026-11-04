# Molido Mesh AI — iOS

Native iOS app and Network Extension Packet Tunnel for the Molido Aether transport core.

## Architecture

- SwiftUI app manages the Molido VPN profile and non-secret App Group bootstrap.
- Packet Tunnel extension prepares the Aether identity, starts MASQUE or WireGuard, installs validated IPv4/IPv6 default routes and DNS, then bridges system packets through `NEPacketTunnelFlow`.
- Rust Aether core provides bounded packet queues and the live MASQUE/WireGuard packet transport.
- A private App Group engine directory stores Aether's persisted identity and certificate material.

## Build

Requires macOS, Xcode 16.4 (as selected by CI), iOS 16 SDK, Rust Apple targets and XcodeGen.

```bash
brew install xcodegen
cd ios-app
xcodegen generate
xcodebuild -project MolidoMeshAI.xcodeproj -scheme MolidoMeshAI -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

The GitHub Actions workflow cross-compiles the Rust static library for iPhone arm64 and simulator architectures, packages `Aether.xcframework`, then builds the app and Packet Tunnel extension.

## Security and route policy

- The versioned bootstrap stores protocol choice, DNS, MTU and a safe identity filename; it contains no secrets.
- Aether owns personal identity provisioning and persists its keys, tokens and certificates in the protected App Group engine directory.
- The directory and files use iOS data protection and restrictive POSIX permissions; symlinks are rejected.
- The saved `NETunnelProviderProtocol` enables `includeAllNetworks` and explicitly keeps local-network, APNs, and cellular-service exclusions disabled. iOS drops eligible device traffic while this VPN is disconnected or reconnecting; Apple-defined control-plane and captive-portal traffic remains exempt.
- The provider applies IPv4 and IPv6 default routes and catch-all DNS only after the native engine reports ready.
- The current iOS build supports full-tunnel routing only. Split-tunnel requests are rejected until a validated route policy exists.
- Queue saturation is treated as backpressure and retained within a bounded handoff buffer; malformed packets, exhausted buffers, native failure and packet-flow errors fail closed.
- After connection, the app makes a fresh ephemeral HTTPS request to Cloudflare trace and displays the egress IP only when the response confirms `warp=on`.
- The app does not display fabricated latency or throughput values.

## Current scope and limitations

- Native SwiftUI home screen and VPN profile lifecycle.
- Integrated Aether preparation/start/stop lifecycle and IPv4/IPv6 packet adapter.
- MASQUE and WireGuard engine modes in the bootstrap schema.
- Full-tunnel routes, DNS and MTU configuration.
- No claim of production certification: physical iPhone leak testing, carrier handover tests, Apple Network Extension entitlement approval, Team provisioning, signing and TestFlight distribution remain outstanding.
- fa/en/ar/tr localization and RTL validation remain outstanding.

See [TRANSPORT_INTEGRATION.md](TRANSPORT_INTEGRATION.md) for the implementation status and validation gates.
