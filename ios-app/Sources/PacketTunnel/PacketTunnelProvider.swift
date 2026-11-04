import Foundation
import NetworkExtension

/// Calls the NetworkExtension start completion at most once across asynchronous
/// preparation, engine startup, network-settings application, and packet activation.
private final class MolidoStartCompletion {
    private let lock = NSLock()
    private var handler: ((Error?) -> Void)?

    init(_ handler: @escaping (Error?) -> Void) {
        self.handler = handler
    }

    func finish(_ error: Error?) {
        lock.lock()
        let callback = handler
        handler = nil
        lock.unlock()
        callback?(error)
    }
}

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private var packetAdapter: MolidoAetherPacketAdapter?

    override func startTunnel(
        options: [String: NSObject]?,
        completionHandler: @escaping (Error?) -> Void
    ) {
        let completion = MolidoStartCompletion(completionHandler)

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
            let engineURL = try MolidoTunnelBootstrapStore.engineConfigurationURL(
                for: configuration,
                in: groupID
            )
            let optionsJSON = try MolidoAetherConfigurationBuilder.makeOptionsJSON(
                for: configuration,
                engineConfigurationURL: engineURL
            )

            let adapter = MolidoAetherPacketAdapter()
            packetAdapter = adapter

            // Identity provisioning and the native start call are blocking FFI
            // operations. Keep both off NetworkExtension's lifecycle queue.
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                do {
                    let addresses = try MolidoAetherConfigurationBuilder.prepare(
                        optionsJSON: optionsJSON
                    )
                    adapter.start(
                        engineOptionsJSON: optionsJSON,
                        ready: { result in
                            switch result {
                            case .failure(let error):
                                completion.finish(error)
                            case .success:
                                do {
                                    let settings = try MolidoAetherConfigurationBuilder.makeNetworkSettings(
                                        configuration: configuration,
                                        addresses: addresses
                                    )
                                    self?.setTunnelNetworkSettings(settings) { settingsError in
                                        if let settingsError {
                                            adapter.stop()
                                            completion.finish(settingsError)
                                            return
                                        }
                                        adapter.activatePacketFlow(
                                            self?.packetFlow ?? NEPacketTunnelFlow(),
                                            activated: { activation in
                                                switch activation {
                                                case .success:
                                                    completion.finish(nil)
                                                case .failure(let error):
                                                    adapter.stop()
                                                    completion.finish(error)
                                                }
                                            },
                                            failed: { error in
                                                completion.finish(error)
                                                self?.cancelTunnelWithError(error)
                                            }
                                        )
                                    }
                                } catch {
                                    adapter.stop()
                                    completion.finish(error)
                                }
                            }
                        },
                        failed: { [weak self] error in
                            completion.finish(error)
                            self?.cancelTunnelWithError(error)
                        }
                    )
                } catch {
                    completion.finish(error)
                }
            }
        } catch {
            completion.finish(error)
        }
    }

    override func stopTunnel(
        with reason: NEProviderStopReason,
        completionHandler: @escaping () -> Void
    ) {
        guard let adapter = packetAdapter else {
            completionHandler()
            return
        }
        packetAdapter = nil
        adapter.stop(completion: completionHandler)
    }
}
