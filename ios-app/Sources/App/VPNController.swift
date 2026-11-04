import Foundation
import Combine
import NetworkExtension

@MainActor
final class VPNController: ObservableObject {
    @Published private(set) var status: NEVPNStatus = .invalid
    @Published private(set) var message = "آماده‌سازی اتصال"
    @Published private(set) var profileInstalled = false

    private var manager: NETunnelProviderManager?
    private let providerBundleIdentifier = "com.molido.meshai.PacketTunnel"

    init() {
        refresh()
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(vpnStatusChanged),
            name: .NEVPNStatusDidChange,
            object: nil
        )
    }

    func refresh() {
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, error in
            Task { @MainActor in
                guard let self else { return }
                if let error {
                    self.message = "خواندن تنظیمات VPN ناموفق بود: \(error.localizedDescription)"
                    return
                }
                self.manager = managers?.first(where: { manager in
                    (manager.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == self.providerBundleIdentifier
                })
                self.profileInstalled = self.manager != nil
                self.status = self.manager?.connection.status ?? .invalid
            }
        }
    }

    /// Installs only Molido's profile. Secrets are never placed in providerConfiguration.
    func prepareProfile() {
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, error in
            Task { @MainActor in
                guard let self else { return }
                if let error {
                    self.message = "خطا در بارگذاری پروفایل: \(error.localizedDescription)"
                    return
                }

                do {
                    _ = try MolidoTunnelBootstrapStore.prepareEngineStorage()
                    let bootstrap = MolidoTunnelConfiguration(
                        version: MolidoTunnelConfiguration.currentVersion,
                        engineProtocol: .masque,
                        engineConfigurationFile: "masque-identity.toml",
                        keychainService: nil,
                        keychainAccount: nil,
                        ipv4CIDR: nil,
                        ipv6CIDR: nil,
                        dnsServers: ["1.1.1.1", "1.0.0.1"],
                        mtu: 1280,
                        includeDefaultRoute: true
                    )
                    _ = try MolidoTunnelBootstrapStore.save(bootstrap)
                } catch {
                    self.message = "آماده‌سازی فضای امن موتور ناموفق بود: \\(error.localizedDescription)"
                    return
                }

                let existingMolidoManager = managers?.first(where: { manager in
                    (manager.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == self.providerBundleIdentifier
                })
                let manager = existingMolidoManager ?? NETunnelProviderManager()
                let tunnel = NETunnelProviderProtocol()
                tunnel.providerBundleIdentifier = self.providerBundleIdentifier
                tunnel.serverAddress = "Molido Mesh AI"
                tunnel.providerConfiguration = [
                    "profileVersion": MolidoTunnelConfiguration.currentVersion,
                    "appGroupIdentifier": MolidoTunnelConfiguration.appGroupIdentifier
                ]

                manager.protocolConfiguration = tunnel
                manager.localizedDescription = "Molido Mesh AI"
                manager.isEnabled = true
                manager.saveToPreferences { saveError in
                    Task { @MainActor in
                        if let saveError {
                            self.message = "ذخیره پروفایل ناموفق بود: \(saveError.localizedDescription)"
                            return
                        }
                        manager.loadFromPreferences { reloadError in
                            Task { @MainActor in
                                if let reloadError {
                                    self.message = "بازخوانی پروفایل ناموفق بود: \(reloadError.localizedDescription)"
                                    return
                                }
                                self.manager = manager
                                self.profileInstalled = true
                                self.status = manager.connection.status
                                self.message = "پروفایل امن Molido آماده شد؛ موتور هنگام اتصال شناسه تونل را آماده می‌کند."
                            }
                        }
                    }
                }
            }
        }
    }

    func connect() {
        guard profileInstalled, let manager else {
            message = "ابتدا پروفایل VPN را آماده کنید."
            return
        }
        do {
            try manager.connection.startVPNTunnel()
            message = "درخواست اتصال ارسال شد؛ افزونه اعتبارسنجی تنظیمات را انجام می‌دهد."
        } catch {
            message = "اتصال برقرار نشد: \(error.localizedDescription)"
        }
    }

    func disconnect() {
        manager?.connection.stopVPNTunnel()
        message = "درخواست قطع اتصال ارسال شد."
    }

    @objc private func vpnStatusChanged() {
        refresh()
    }

    var statusLabel: String {
        switch status {
        case .connected: return "متصل"
        case .connecting, .reasserting: return "در حال اتصال"
        case .disconnecting: return "در حال قطع"
        case .disconnected: return "قطع"
        case .invalid: return "پروفایل نصب نشده"
        @unknown default: return "نامشخص"
        }
    }
}
