# Molido Mesh AI — iOS

This directory is the first native iOS application scaffold. It is intentionally isolated from the Android Gradle project.

## Architecture

- **SwiftUI app:** native iPhone presentation and VPN profile lifecycle.
- **Network Extension:** a dedicated `NEPacketTunnelProvider` extension target.
- **Shared product services:** the planned integration point for the Molido node registry, signed configuration feed, and device/network-aware route scoring.
- **Transport core:** not yet ported. The current provider deliberately returns an error and does not install routes or claim a working VPN connection.

## Build

Requires macOS, Xcode 15 or newer, iOS 16 SDK, and XcodeGen.

```bash
brew install xcodegen
cd ios-app
xcodegen generate
xcodebuild -project MolidoMeshAI.xcodeproj -scheme MolidoMeshAI -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

For a physical-device build and distribution, configure an Apple Developer Team, the Network Extension capability, provisioning profiles, and the matching App ID entitlements in the Apple Developer account. The entitlements in this scaffold are declarations, not proof that Apple has granted the capability.

## Current scope

- Native dark SwiftUI home screen with Molido branding.
- VPN profile creation through `NETunnelProviderManager`.
- Honest connection state; no fabricated latency, throughput, or connected status.
- Packet tunnel extension target and explicit fail-closed placeholder.
- No production VPN transport, node discovery, server credentials, or release signing yet.

## Next engineering gates

1. Add a macOS CI job to generate the Xcode project and compile both targets.
2. Port/build the existing Rust networking core for supported iOS device architectures and audit dependencies.
3. Implement packet I/O, DNS routing, IPv4/IPv6 handling, MTU, reconnect and kill-switch semantics.
4. Integrate signed node metadata and local-only network measurements.
5. Add fa/en/ar/tr localization and RTL validation for Persian and Arabic.
6. Test on physical iPhones over Wi-Fi and cellular, including handovers and leak checks.
7. Configure Apple Network Extension entitlements and distribute through TestFlight before App Store review.

Android behavior and release signing are not changed by this iOS scaffold.
