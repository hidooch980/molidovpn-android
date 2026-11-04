# iOS transport integration status

## Native packet path

The iOS Packet Tunnel uses the Aether Rust core through its dedicated C ABI:

- `aether_prepare_json` provisions or loads the selected MASQUE/WireGuard identity and returns the assigned IPv4/IPv6 CIDRs.
- `aether_start_json_with_packet_channel` starts the native transport without an Android file descriptor.
- `packet_channel::spawn_bridge` connects Aether's transport channels to bounded, thread-safe FFI queues.
- `MolidoAetherPacketAdapter` moves packets between those queues and `NEPacketTunnelFlow`.
- `PacketTunnelProvider` applies full-tunnel IPv4/IPv6 routes and DNS only after Aether reports readiness, then activates packet pumps.

The Android-only `aether_start_json_with_tun(json, fd)` API is not used by iOS. The generic userspace/SOCKS `aether_start_json` API is not a substitute for the system packet tunnel.

## Safety and lifecycle behavior

- First-run identity provisioning and native startup run off the NetworkExtension lifecycle queue.
- Bootstrap JSON contains no credentials. Aether persists its identity, keys, tokens and certificates beneath the protected App Group engine directory.
- Engine files are protected with `completeUntilFirstUserAuthentication`; directories use restrictive permissions and symlinks are rejected.
- The saved `NETunnelProviderProtocol` enables `includeAllNetworks` and explicitly disables local-network, APNs and cellular-service exclusions. iOS drops eligible traffic while the VPN is disconnected or reconnecting; Apple-defined control-plane and captive-portal traffic remains exempt.
- IPv4 and IPv6 default routes and catch-all DNS are installed only after native readiness. The initial iOS route policy is full tunnel only; unsupported split routing is rejected rather than allowed to leak.
- Inbound queue saturation is handled as backpressure: already-read packets are retained in a bounded local queue and retried. The tunnel fails closed if that bounded handoff buffer itself is exhausted.
- Native exit after readiness, loss of readiness, malformed packets, queue errors and packet-flow write failures terminate the tunnel.
- After NetworkExtension reports connected, the app uses a fresh ephemeral HTTPS session to Cloudflare trace and only marks egress verified when a valid exit IP and `warp=on` are returned.
- Shutdown requests native stop and waits for the blocking FFI worker to return before completing provider teardown.

## Confirmed validation

Aether Rust unit tests (including packet-channel tests) and Apple-target cross-compilation have passed in recent runs. A fresh macOS/Xcode app/extension build and the Android validation and packet-regression workflows are being rerun for the latest integration commits. CI does not prove Apple entitlement approval, signing, real network reachability, or absence of leaks on physical devices.

## Remaining production gates

1. Fresh iOS CI build after the provider/adapter integration changes.
2. Physical iPhone tests on Wi-Fi and cellular, including IPv4-only, IPv6-only and dual-stack networks.
3. Verify public egress IP, DNS resolver path, IPv6 routing, reconnect, network handover, and fail-closed behavior by observing traffic outside and inside the tunnel.
4. Validate Apple Developer Team, Packet Tunnel Network Extension capability, App Group entitlement and provisioning profiles for both app and extension.
5. Test app/extension lifecycle across device restart, first unlock, force quit, low-memory termination and OS updates.
6. Complete fa/en/ar/tr localization and RTL layout checks before public distribution.

Until those physical-device and Apple-account gates pass, this is an integrated engineering build, not a certified production VPN release.
