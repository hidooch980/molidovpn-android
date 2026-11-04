# iOS transport integration notes

## Native core boundary

The existing Aether library exports a C ABI from `core/aether/src/ffi.rs`. The public declarations are mirrored in `core/aether/include/aether.h` so Swift can call the stable lifecycle and event APIs after the static library is linked.

Important distinction:

- `aether_start_json_with_tun(json, fd)` is the Android Unix-fd bridge. It is **not** an iOS API.
- `aether_start_json(json)` starts the userspace/SOCKS mode; it does not attach iOS system packet flow.
- `NEPacketTunnelFlow` exposes packet reads/writes through NetworkExtension and cannot be substituted with a file descriptor.

## Rust packet-channel bridge and Swift adapter implemented; provider integration remains gated

A bounded, thread-safe FFI queue primitive now exists in `core/aether/src/packet_channel.rs` and is declared in `core/aether/include/aether.h`. It carries IPv4/IPv6 family metadata, limits each packet to 65,535 bytes, caps each direction at 256 packets / 2 MiB, reports backpressure, and does not consume a packet when the caller's output buffer is too small. Unit tests cover byte/family preservation, buffer sizing, and queue bounds.

The queue primitive is connected to Aether's existing packet channels through `packet_channel::spawn_bridge`. The dedicated `aether_start_json_with_packet_channel` C ABI starts MASQUE or WireGuard without accepting a Unix fd. The bridge forwards device-originated packets into the native transport and queues decrypted packets for the provider, with bounded backpressure and task cancellation. Rust-side queue and bridge tests are present, and the new `MolidoAetherPacketAdapter` imports `AetherFFI`, runs the blocking start call on a dedicated worker, waits for native readiness with a startup deadline, moves IPv4/IPv6 packets through `NEPacketTunnelFlow`, handles bounded-queue backpressure as a fatal condition, and requests deterministic native shutdown. The adapter is not yet wired into `PacketTunnelProvider`; it must only be activated after validated `NEPacketTunnelNetworkSettings` are applied. The iOS CI pipeline now cross-compiles the Rust static library for iPhone arm64 and both simulator architectures, packages the device and universal simulator slices into `Aether.xcframework`, publishes it as a workflow artifact, and links that artifact into the Packet Tunnel extension through XcodeGen. The `AetherFFI` Clang module map exposes `aether.h`. This is a build/link milestone only, not yet a functioning iOS VPN.

Remaining work:

1. Verify the new XCFramework packaging and extension-linking jobs pass on macOS CI; retain the artifact for reproducible local Xcode builds.
2. Complete the protocol-specific config translator that maps validated MASQUE/WireGuard profiles and Keychain-held credentials into the exact `NativeStartOptions` JSON contract. Never assume Android filesystem paths or Android-only configuration fields.
4. Wire `MolidoAetherPacketAdapter` into `NEPacketTunnelProvider` after the protocol-specific config translator is complete. Its blocking FFI call already runs on a dedicated worker thread.
5. Apply validated network settings only after native readiness, then call `activatePacketFlow`; the adapter uses `packetFlow.readPackets` / `writePackets` and the bounded inbound/outbound FFI queues.
6. Preserve IPv4/IPv6 policy and DNS routing; configure `NEPacketTunnelNetworkSettings` only from validated address and route data. Do not install a default route before the native data path is ready.
7. Implement socket protection/routing behavior required by NetworkExtension to prevent recursive tunnel routing.
8. Stop packet pumps and the Rust runtime deterministically from `stopTunnel`; drain/cancel worker tasks and prevent callbacks after provider teardown.
9. Emit `connected` only after Aether reports readiness and a packet exchange is verified; fail closed on timeout, invalid configuration, queue saturation, or native startup errors.

## Current safety state

The Swift Packet Tunnel provider intentionally fails closed. The packet adapter is implemented as an isolated component but is not yet invoked by the provider. The app can install a VPN profile, but the provider does not claim a live connection or configure routes until protocol-specific config translation, provider lifecycle integration, and physical-device leak testing are complete.

## Validation gates

- Rust static library builds for iPhone arm64 and simulator targets; package and link the XCFramework into the extension.
- Unit tests for queue bounds, cancellation, malformed packets, and IPv4/IPv6 metadata.
- Simulator build for both app and extension with the Rust symbols resolved at link time.
- Physical-device tests on Wi-Fi and cellular, including handovers, DNS leak, IPv6 leak, kill-switch, and reconnect behavior.
- Apple Developer Team, Packet Tunnel Network Extension entitlement, matching App IDs/profiles, and TestFlight signing are verified before distribution.
