# iOS transport integration notes

## Native core boundary

The existing Aether library exports a C ABI from `core/aether/src/ffi.rs`. The public declarations are mirrored in `core/aether/include/aether.h` so Swift can call the stable lifecycle and event APIs after the static library is linked.

Important distinction:

- `aether_start_json_with_tun(json, fd)` is the Android Unix-fd bridge. It is **not** an iOS API.
- `aether_start_json(json)` starts the userspace/SOCKS mode; it does not attach iOS system packet flow.
- `NEPacketTunnelFlow` exposes packet reads/writes through NetworkExtension and cannot be substituted with a file descriptor.

## Required iOS packet adapter (not yet implemented)

A real iOS tunnel requires a native packet-channel API in Aether, then a Swift adapter:

1. Extend Aether start options with an explicit packet-channel mode. Never overload a valid fd or silently fall back to SOCKS.
2. Add bounded, back-pressured packet queues across the FFI boundary. Define ownership, packet-size limits (IPv4/IPv6 maximum), cancellation, and queue-full behavior.
3. In `NEPacketTunnelProvider`, read packets using `packetFlow.readPackets`, enqueue them to Aether, dequeue Aether's outbound packets, and return them using `packetFlow.writePackets`.
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
