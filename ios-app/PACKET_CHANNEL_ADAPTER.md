# iOS packet-channel adapter

`MolidoPacketChannelAdapter` is the Swift boundary between
`NEPacketTunnelFlow` and the bounded Aether C queues.

## Lifecycle contract

1. Validate the App Group bootstrap and resolve the engine configuration.
2. Load the referenced secret from the shared Keychain; never persist it in the
   bootstrap or `NETunnelProviderProtocol.providerConfiguration`.
3. Prepare and start Aether on a dedicated worker queue using a validated
   protocol-specific options document.
4. Wait for `aether_is_ready() == 1` with a bounded startup deadline.
5. Apply `NEPacketTunnelNetworkSettings` only after the prepared address/DNS/MTU
   values are validated and the native engine is ready.
6. Start `MolidoPacketChannelAdapter` to pump packets in both directions.
7. On stop or fatal queue/packet error: stop packet pumps first, call
   `aether_stop()` off the Network Extension callback queue, wait for the native
   worker to finish, then reset the packet queues.

## Packet handling

- IPv4 and IPv6 family are derived from the IP header and cross-checked against
  the protocol metadata supplied by Network Extension.
- The adapter uses the C ABI's 65,535-byte packet bound and drains at most 128
  outbound packets per timer tick.
- A saturated inbound queue is treated as fatal rather than silently dropping
  packets and pretending the tunnel is healthy.
- The adapter does not install routes, configure DNS, or claim connection
  readiness. Those remain the provider's responsibility after successful native
  preparation and readiness checks.

## Remaining integration gate

The Rust start API accepts a JSON `NativeStartOptions` document. Its
protocol-specific config paths and credential fields must be mapped from the
validated bootstrap and Keychain data before invoking it. That mapping and
blocking start/stop worker lifecycle are not yet wired into
`PacketTunnelProvider`; until then the provider intentionally fails closed.
