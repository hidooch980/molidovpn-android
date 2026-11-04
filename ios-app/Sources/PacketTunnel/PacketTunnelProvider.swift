import Foundation
import NetworkExtension

/// The provider deliberately fails closed until a validated bootstrap, engine
/// configuration, shared Keychain credential, and native packet adapter exist.
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

            let configuration = try MolidoTunnelConfiguration.load(from: groupID)
            _ = try MolidoTunnelBootstrapStore.engineConfigurationURL(
                for: configuration,
                in: groupID
            )
            let credential = try MolidoKeychainStore.load(
                service: configuration.keychainService,
                account: configuration.keychainAccount
            )
            guard !credential.isEmpty else {
                throw MolidoTunnelConfigurationError.credentialMissing
            }

            // Never copy credentials into providerConfiguration or bootstrap JSON.
            // The Aether packet-channel bridge remains a hard gate before routes
            // can be installed or the provider can report a working tunnel.
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
