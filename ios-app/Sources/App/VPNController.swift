import Foundation
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
                self.manager = managers?.first
                self.profileInstalled = self.manager != nil
                self.status = self.manager?.connection.status ?? .invalid
            }
        }
    }

    /// Installs the iOS VPN profile. It does not claim a live tunnel until
    /// the shared Molido transport core is integrated and verified.
    func prepareProfile() {
        NETunnelProviderManager.loadAllFromPreferences { [weak self] managers, error in
            Task { @MainActor in
                guard let self else { return }
                if let error {
                    self.message = "خطا در بارگذاری پروفایل: \(error.localizedDescription)"
                    return
                }

                let manager = managers?.first ?? NETunnelProviderManager()
                let tunnel = NETunnelProviderProtocol()
                tunnel.providerBundleIdentifier = self.providerBundleIdentifier
                tunnel.serverAddress = "Molido Mesh AI"
                tunnel.providerConfiguration = ["profileVersion": 1]

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
                                self.message = "پروفایل Molido آماده شد؛ موتور تونل هنوز باید متصل و آزمایش شود."
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
            message = "درخواست اتصال ارسال شد؛ وضعیت واقعی تونل در حال بررسی است."
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
