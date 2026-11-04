# Molido Mesh AI — iOS

This directory is the first native iOS application scaffold. It is intentionally isolated from the Android Gradle project.

## Architecture

- **SwiftUI app:** native iPhone presentation and VPN profile lifecycle.
- **Network Extension:** a dedicated `NEPacketTunnelProvider` extension target.
- **Shared product services:** the planned integration point for the Molido node registry, signed configuration feed, and device/network-aware route scoring.
- **Transport core:** the Rust Aether library is cross-compiled for iOS device and simulator targets and packaged as an XCFramework. The provider still fails closed until the packet adapter is integrated.

## Build

Requires macOS, Xcode 15 or newer, iOS 16 SDK, and XcodeGen.

```bash
brew install xcodegen
cd ios-app
xcodegen generate
xcodebuild -project MolidoMeshAI.xcodeproj -scheme MolidoMeshAI -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

For a physical-device build and distribution, configure an Apple Developer Team, the Network Extension capability, provisioning profiles, and matching App Group and Keychain access-group entitlements in the Apple Developer account. The entitlement files in this scaffold are declarations, not proof that Apple has granted the capabilities. The shared Keychain group is configured as `$(AppIdentifierPrefix)com.molido.meshai.shared`; the Team/App Identifier Prefix must match the provisioning profiles for both targets.

## Security contract

- The App Group bootstrap is versioned and contains configuration references, never credentials.
- Secrets are stored as opaque data in the shared Keychain access group using `AfterFirstUnlockThisDeviceOnly`.
- `MolidoTunnelBootstrapStore` writes the bootstrap atomically and applies file protection suitable for a tunnel that starts after the first device unlock.
- The engine configuration file must be provisioned separately in the App Group. A missing file is a hard failure; no sample credentials or fabricated profile are installed.
- The provider does not claim connected state or install routes while the native packet adapter is incomplete.

## Current scope

- Native dark SwiftUI home screen with Molido branding.
- VPN profile creation through `NETunnelProviderManager`.
- Honest connection state; no fabricated latency, throughput, or connected status.
- Packet tunnel extension target with fail-closed startup validation.
- Swift packet-channel adapter bridging Network Extension packets to Aether's bounded IPv4/IPv6 queues; it is not started until native engine readiness is wired.
- Shared Keychain helper and atomic bootstrap storage contract.
- No production VPN transport, node discovery, server credentials, or release signing yet.

## Next engineering gates

1. Wire the packet-channel adapter to the Aether start/stop worker and readiness state machine.
2. Map MASQUE/WireGuard configuration and Keychain credentials into validated native options without writing secrets to disk.
3. Apply packet tunnel network settings only after native preparation/readiness; implement DNS, IPv4/IPv6, MTU, reconnect and kill-switch semantics.
4. Integrate signed node metadata and local-only network measurements.
5. Add fa/en/ar/tr localization and RTL validation for Persian and Arabic.
6. Test on physical iPhones over Wi-Fi and cellular, including handovers and DNS/IP leak checks.
7. Configure Apple Network Extension entitlements and distribute through TestFlight before App Store review.

Android behavior and release signing are not changed by this iOS scaffold.
