# iOS transport integration notes

## Native core boundary

The existing Aether library exports a C ABI from `core/aether/src/ffi.rs`. The public declarations are mirrored in `core/aether/include/aether.h` so Swift can call the stable lifecycle and event APIs after the static library is linked.

Important distinction:

- `aether_start_json_with_tun(json, fd)` is the Android Unix-fd bridge. It is **not** an iOS API.
- `aether_start_json(json)` starts the userspace/SOCKS mode; it does not attach iOS system packet flow.
- `NEPacketTunnelFlow` exposes packet reads/writes through NetworkExtension and cannot be substituted with a file descriptor.

## Packet queue primitive added; transport adapter still required

A bounded, thread-safe FFI queue primitive now exists in `core/aether/src/packet_channel.rs` and is declared in `core/aether/include/aether.h`. It carries IPv4/IPv6 family metadata, limits each packet to 65,535 bytes, caps each direction at 256 packets / 2 MiB, reports backpressure, and does not consume a packet when the caller's output buffer is too small. Unit tests cover byte/family preservation, buffer sizing, and queue bounds.

The queue primitive is now connected to Aether's existing packet channels through `packet_channel::spawn_bridge`, and a dedicated `aether_start_json_with_packet_channel` C ABI starts MASQUE or WireGuard without accepting a Unix fd. The bridge forwards device-originated packets into the native transport and queues decrypted packets for the provider, with bounded backpressure and task cancellation. The Swift provider still does not call this API, and the native library is not yet linked into the Xcode target; therefore this is a Rust-side integration milestone, not a functioning iOS VPN. Remaining work:

1. Extend Aether start options with an explicit packet-channel mode. Never overload a valid fd or silently fall back to SOCKS.
2. Link the Rust static library into the Xcode app-extension target and expose the C header through a module map/bridging header. Preserve the dedicated packet-channel start API.
3. In `NEPacketTunnelProvider`, call `aether_start_json_with_packet_channel`, read packets using `packetFlow.readPackets`, enqueue them to Aether, dequeue Aether's outbound packets, and return them using `packetFlow.writePackets`.
4. Preserve packet protocol-family metadata and IPv4/IPv6 routing. Apply DNS, MTU, included/excluded routes, and IPv6 policy from validated tunnel configuration.
5. Bind socket protection to NetworkExtension's required routing model; avoid recursive tunnel routing.
6. Stop both packet pumps and the Rust runtime deterministically from `stopTunnel`; ensure no stale callbacks reach a deallocated provider.
7. Emit `connected` only after Aether reports its real data path ready and packet exchange is verified.

## Current safety state

The Swift Packet Tunnel provider intentionally fails closed. The app can install a VPN profile, but the provider does not claim a live connection, configure a default route, or black-hole device traffic until the packet adapter and transport are complete.

## Validation gates

- Rust static library builds for iPhone arm64 and simulator targets.
- Unit tests for queue bounds, cancellation, malformed packets, and IPv4/IPv6 metadata.
- Simulator build for both app and extension.
- Physical-device tests on Wi-Fi and cellular, including handovers, DNS leak, IPv6 leak, kill-switch, and reconnect behavior.
