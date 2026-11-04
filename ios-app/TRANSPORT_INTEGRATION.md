# iOS transport integration status

## Native boundary

- `aether_start_json_with_tun(json, fd)` is the Android Unix-fd bridge and is not used by iOS.
- `aether_start_json(json)` is the userspace/SOCKS mode and does not attach iOS system packet flow.
- iOS uses `aether_start_json_with_packet_channel(json)`, the bounded FFI packet queues, and `NEPacketTunnelFlow`.

## Implemented in this branch

1. Rust packet-channel queues preserve IPv4/IPv6 family metadata, cap packet size at 65,535 bytes, and bound each direction to 256 packets / 2 MiB.
2. `packet_channel::spawn_bridge` connects those queues to Aether's existing MASQUE and WireGuard packet paths.
3. `MolidoAetherConfigurationBuilder` builds the actual `NativeStartOptions` schema, calls `aether_prepare_json` on a worker to provision/load identity, validates returned IPv4/IPv6 CIDRs, and creates full-tunnel NetworkExtension settings.
4. `PacketTunnelProvider` starts the native engine on a worker, waits for readiness, installs IPv4+IPv6 default routes and catch-all DNS only after readiness, then activates the packet pumps.
5. `MolidoAetherPacketAdapter` preserves inbound packets under native queue backpressure, rejects malformed/family-mismatched packets, monitors runtime readiness, and waits for native worker shutdown during teardown.
6. Engine identity files live in the App Group's data-protected private directory. The bootstrap contains no secrets.

## Important limitations / production gates

- CI proves the Rust library cross-compiles for Apple targets and the app/extension compile and link for Simulator. It does not prove the Apple Network Extension entitlement has been granted or that a signed app can install on a device.
- Real packet forwarding, DNS behavior, endpoint socket routing/recursion prevention, IPv6, kill-switch semantics, reconnect and network handover must be tested on physical iPhones over Wi-Fi and cellular.
- The current iOS bootstrap defaults to MASQUE. WireGuard can be selected in the native configuration model, but there is no user-facing WireGuard profile import flow yet.
- Split tunneling is intentionally rejected. The current provider supports only full-tunnel IPv4+IPv6 routing.
- No TestFlight/App Store release signing is configured in this branch.

## Validation

- iOS CI cross-compiles Aether for iPhone arm64 and simulator architectures, packages the XCFramework, and builds the SwiftUI app plus Packet Tunnel extension.
- Android CI runs unit tests and builds unsigned release APKs.
- Rust packet-channel tests are being added to the Android validation pipeline so queue semantics are exercised, not merely compiled.
- Physical-device leak and sustained-traffic tests remain mandatory before calling this a production VPN.
