import NetworkExtension

/// Safe scaffold only. The Android Rust transport is not yet compiled for iOS;
/// refusing to create a default route prevents a false "connected" state or
/// accidental black-holing of all device traffic.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        let error = NSError(
            domain: "ai.molido.meshai.tunnel",
            code: 1001,
            userInfo: [NSLocalizedDescriptionKey:
                "Molido iOS transport core is not integrated yet. No traffic was routed."]
        )
        completionHandler(error)
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        completionHandler()
    }
}
