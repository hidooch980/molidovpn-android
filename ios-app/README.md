# Molido Mesh AI — iOS

Native iOS app and Network Extension Packet Tunnel integration for Molido Mesh AI.

## Architecture

- **SwiftUI app:** native iPhone UI and VPN profile lifecycle.
- **Network Extension:** dedicated `NEPacketTunnelProvider`.
- **Native transport:** the shared Rust Aether library, linked as an XCFramework for iPhone arm64 and iOS Simulator.
- **Packet bridge:** Aether's bounded IPv4/IPv6 queues connected to `NEPacketTunnelFlow` by `MolidoAetherPacketAdapter`.

## Current connection path

1. The app creates a versioned, non-secret bootstrap in the App Group and prepares a private `engine/` directory.
2. The provider validates its bundle profile, App Group, bootstrap, DNS list, MTU, and full-tunnel-only route policy.
3. `MolidoAetherConfigurationBuilder` generates the exact `NativeStartOptions` JSON expected by `core/aether/src/ffi.rs`.
4. On a worker queue, `aether_prepare_json` provisions or loads the selected MASQUE/WireGuard identity and returns assigned IPv4/IPv6 CIDRs.
5. The provider starts `aether_start_json_with_packet_channel` off the NetworkExtension lifecycle queue and waits for Aether readiness.
6. Only after readiness, it installs IPv4 and IPv6 default routes, DNS servers with a catch-all match domain, and the validated MTU.
7. The packet adapter starts the inbound/outbound `NEPacketTunnelFlow` pumps, preserves inbound batches during native queue backpressure, and monitors native runtime health.
8. `stopTunnel` requests native shutdown and waits for the blocking worker to return before completing teardown.

The iOS bootstrap defaults to Aether MASQUE. WireGuard is supported by the native options builder, but no iOS UI for importing a user WireGuard profile is exposed yet.

## Build

Requires macOS, Xcode 16.4 in CI (Xcode 15+ may work locally), iOS 16 SDK, and XcodeGen.

```bash
brew install xcodegen
cd ios-app
xcodegen generate
xcodebuild -project MolidoMeshAI.xcodeproj -scheme MolidoMeshAI -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

The GitHub Actions workflow cross-compiles Aether for iPhone arm64 and simulator architectures, packages the XCFramework, and builds the app and extension. A successful simulator build does not prove that Apple has granted the Network Extension entitlement.

## Security and routing

- The bootstrap contains no account secrets. Aether persists its MASQUE/WireGuard identity beneath the App Group's private `engine/` directory.
- The engine directory and identity files receive iOS Data Protection (`completeUntilFirstUserAuthentication`) and restrictive POSIX permissions.
- Symlinks are rejected when resolving the engine identity file.
- The provider accepts full-tunnel routing only. It installs both IPv4 and IPv6 default routes and routes DNS through the configured tunnel DNS servers. Unsupported split routing fails closed.
- The adapter treats malformed packets, queue errors, and native runtime loss as fatal and stops the tunnel rather than silently dropping traffic.
- The UI displays actual NetworkExtension status; it does not fabricate ping, download, or upload metrics.

## Remaining production gates

- Validate DNS/IP leak behavior, kill-switch semantics, reconnect, Wi-Fi/cellular handover, and sustained traffic on physical iPhones.
- Verify Apple Developer Team, Network Extension capability, App Group, and bundle provisioning on a real developer account.
- Validate that the Aether transport's own sockets remain outside the packet tunnel on every supported iOS version and network type.
- Add app-specific localization (fa/en/ar/tr), RTL validation, signed node registry integration, and WireGuard import UI.
- Configure TestFlight signing and distribution; no signed iOS release is produced by the unsigned simulator workflow.

Android behavior and the existing Android signing identity are not modified by this iOS integration branch.
