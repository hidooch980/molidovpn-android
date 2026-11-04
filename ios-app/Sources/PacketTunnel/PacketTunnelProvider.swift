import Foundation
import NetworkExtension

/// The provider deliberately fails closed until a validated bootstrap exists
/// and the native packet-channel adapter is ready.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        do {
            guard
                let tunnelProtocol = protocolConfiguration as? NETunnelProviderProtocol,
                let providerConfiguration = tunnelProtocol.providerConfiguration,
                let version = providerConfiguration["profileVersion"] as? Int,
                version == MolidoTunnelConfiguration.currentVersion,
                let groupID = providerConfiguration["appGroupIdentifier"] as? String,
                groupID == MolidoTunnelConfiguration.appGroupIdentifier
            else {
                throw MolidoTunnelConfigurationError.invalidProviderProfile
            }

            guard FileManager.default.containerURL(
                forSecurityApplicationGroupIdentifier: groupID
            ) != nil else {
                throw MolidoTunnelConfigurationError.sharedContainerUnavailable
            }

            _ = try MolidoTunnelConfiguration.load(from: groupID)
            throw MolidoTunnelConfigurationError.nativePacketAdapterNotReady
        } catch {
            completionHandler(error as NSError)
        }
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        completionHandler()
    }
}
