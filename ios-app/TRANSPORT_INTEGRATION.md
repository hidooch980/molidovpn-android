# iOS transport integration notes

## Native core boundary

The existing Aether library exports a C ABI from `core/aether/src/ffi.rs`. The public declarations are mirrored in `core/aether/include/aether.h` so Swift can call the stable lifecycle and event APIs after the static library is linked.

Important distinction:

- `aether_start_json_with_tun(json, fd)` is the Android Unix-fd bridge. It is **not** an iOS API.
- `aether_start_json(json)` starts the userspace/SOCKS mode; it does not attach iOS system packet flow.
- `NEPacketTunnelFlow` exposes packet reads/writes through NetworkExtension and cannot be substituted with a file descriptor.

## Rust packet-channel bridge implemented; Swift adapter still required

A bounded, thread-safe FFI queue primitive now exists in `core/aether/src/packet_channel.rs` and is declared in `core/aether/include/aether.h`. It carries IPv4/IPv6 family metadata, limits each packet to 65,535 bytes, caps each direction at 256 packets / 2 MiB, reports backpressure, and does not consume a packet when the caller's output buffer is too small. Unit tests cover byte/family preservation, buffer sizing, and queue bounds.

The queue primitive is connected to Aether's existing packet channels through `packet_channel::spawn_bridge`. The dedicated `aether_start_json_with_packet_channel` C ABI starts MASQUE or WireGuard without accepting a Unix fd. The bridge forwards device-originated packets into the native transport and queues decrypted packets for the provider, with bounded backpressure and task cancellation. Rust-side queue and bridge tests are present, but Swift does not yet call this API and the native library is not linked into the Xcode target. This is not yet a functioning iOS VPN.

Remaining work:

1. Build the Rust static library for iPhone arm64 and supported simulator architectures, then package the outputs as an XCFramework.
2. Link that XCFramework into the Packet Tunnel extension target and expose `aether.h` through a module map or bridging header.
3. Define and validate the iOS tunnel configuration contract, including MASQUE/WireGuard credentials, config-file locations, assigned IPv4/IPv6 addresses, DNS, MTU, and routes. Do not assume Android filesystem paths or Android-only configuration fields.
4. In `NEPacketTunnelProvider`, call `aether_start_json_with_packet_channel` on a dedicated worker thread. The FFI start call blocks until shutdown, so it must never run on the provider's main/control queue.
5. Read packets using `packetFlow.readPackets`, enqueue them with `aether_packet_push_inbound`, dequeue native outbound packets with `aether_packet_pop_outbound`, and write them using `packetFlow.writePackets` with the correct address-family metadata.
6. Preserve IPv4/IPv6 policy and DNS routing; configure `NEPacketTunnelNetworkSettings` only from validated address and route data. Do not install a default route before the native data path is ready.
7. Implement socket protection/routing behavior required by NetworkExtension to prevent recursive tunnel routing.
8. Stop packet pumps and the Rust runtime deterministically from `stopTunnel`; drain/cancel worker tasks and prevent callbacks after provider teardown.
9. Emit `connected` only after Aether reports readiness and a packet exchange is verified; fail closed on timeout, invalid configuration, queue saturation, or native startup errors.

## Current safety state

The Swift Packet Tunnel provider intentionally fails closed. The app can install a VPN profile, but the provider does not claim a live connection, configure a default route, or black-hole device traffic until the packet adapter and transport are complete.

## Validation gates

- Rust static library builds for iPhone arm64 and simulator targets; package and link the XCFramework into the extension.
- Unit tests for queue bounds, cancellation, malformed packets, and IPv4/IPv6 metadata.
- Simulator build for both app and extension with the Rust symbols resolved at link time.
- Physical-device tests on Wi-Fi and cellular, including handovers, DNS leak, IPv6 leak, kill-switch, and reconnect behavior.
- Apple Developer Team, Packet Tunnel Network Extension entitlement, matching App IDs/profiles, and TestFlight signing are verified before distribution.
