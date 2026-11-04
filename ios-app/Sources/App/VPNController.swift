import Foundation
import Combine
import Network
import NetworkExtension

@MainActor
final class VPNController: ObservableObject {
    @Published private(set) var status: NEVPNStatus = .invalid
    @Published private(set) var message = "آماده‌سازی اتصال"
    @Published private(set) var profileInstalled = false
    @Published private(set) var egressIP: String?
    @Published private(set) var egressVerified = false

    private var manager: NETunnelProviderManager?
    private var egressVerificationTask: Task<Void, Never>?
    private var lastVerificationAttempt: Date?
    private var preTunnelEgressIP: String?
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
                self.handleStatusChange()
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
                        dnsServers: ["1.1.1.1", "1.0.0.1", "2606:4700:4700::1111"],
                        mtu: 1280,
                        includeDefaultRoute: true
                    )
                    _ = try MolidoTunnelBootstrapStore.save(bootstrap)
                } catch {
                    self.message = "آماده‌سازی فضای امن موتور ناموفق بود: \(error.localizedDescription)"
                    return
                }

                let existingMolidoManager = managers?.first(where: { manager in
                    (manager.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == self.providerBundleIdentifier
                })
                let manager = existingMolidoManager ?? NETunnelProviderManager()
                let tunnel = NETunnelProviderProtocol()
                tunnel.providerBundleIdentifier = self.providerBundleIdentifier
                tunnel.serverAddress = "Molido Mesh AI"
                // Route all device traffic through the packet tunnel. iOS drops traffic
                // while this VPN is disconnected/reconnecting (apart from Apple-defined
                // control-plane and captive-portal exceptions), preventing direct fallback.
                tunnel.includeAllNetworks = true
                tunnel.excludeLocalNetworks = false
                tunnel.excludeAPNs = false
                tunnel.excludeCellularServices = false
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
        guard profileInstalled, let manager,
              let tunnel = manager.protocolConfiguration as? NETunnelProviderProtocol else {
            message = "ابتدا پروفایل VPN را آماده کنید."
            return
        }
        egressVerificationTask?.cancel()
        egressVerificationTask = nil
        egressIP = nil
        egressVerified = false
        lastVerificationAttempt = nil
        preTunnelEgressIP = nil

        // Re-assert fail-closed routing on every connect, including profiles
        // created by older app versions. Never start with a legacy bypass policy.
        tunnel.includeAllNetworks = true
        tunnel.excludeLocalNetworks = false
        tunnel.excludeAPNs = false
        tunnel.excludeCellularServices = false
        manager.protocolConfiguration = tunnel
        message = "در حال اعمال سیاست امن مسیر شبکه…"

        manager.saveToPreferences { [weak self] saveError in
            Task { @MainActor in
                guard let self else { return }
                if let saveError {
                    self.message = "ذخیره سیاست امن شبکه ناموفق بود: \(saveError.localizedDescription)"
                    return
                }
                manager.loadFromPreferences { reloadError in
                    Task { @MainActor in
                        if let reloadError {
                            self.message = "بازخوانی پروفایل امن ناموفق بود: \(reloadError.localizedDescription)"
                            return
                        }
                        self.manager = manager
                        Task { [weak self] in
                            guard let self else { return }
                            self.preTunnelEgressIP = try? await Self.fetchPublicEgressIP()
                            do {
                                try manager.connection.startVPNTunnel()
                                self.message = "درخواست اتصال امن ارسال شد؛ مسیر IPv4/IPv6 و DNS پس از آماده‌شدن موتور فعال می‌شود."
                            } catch {
                                self.message = "اتصال برقرار نشد: \(error.localizedDescription)"
                            }
                        }
                    }
                }
            }
        }
    }

    func disconnect() {
        egressVerificationTask?.cancel()
        egressVerificationTask = nil
        egressIP = nil
        egressVerified = false
        preTunnelEgressIP = nil
        manager?.connection.stopVPNTunnel()
        message = "درخواست قطع اتصال ارسال شد."
    }

    @objc private func vpnStatusChanged() {
        refresh()
    }

    private func handleStatusChange() {
        switch status {
        case .connected:
            verifyRealEgressIfNeeded()
        case .connecting, .reasserting:
            message = "در حال برقراری تونل رمزگذاری‌شده…"
        case .disconnecting, .disconnected, .invalid:
            egressVerificationTask?.cancel()
            egressVerificationTask = nil
            egressIP = nil
            egressVerified = false
        @unknown default:
            break
        }
    }

    /// Compare a fresh public IP before and after connection. Do not require Cloudflare's
    /// WARP-specific flag: generic MASQUE/WireGuard exits are not necessarily WARP exits.
    private func verifyRealEgressIfNeeded() {
        guard egressVerificationTask == nil,
              lastVerificationAttempt == nil ||
              Date().timeIntervalSince(lastVerificationAttempt!) > 15 else { return }
        lastVerificationAttempt = Date()
        message = "تونل فعال است؛ در حال مقایسه IP خروجی با وضعیت پیش از اتصال…"

        egressVerificationTask = Task { [weak self] in
            defer { self?.egressVerificationTask = nil }
            do {
                let ip = try await Self.fetchPublicEgressIP()
                guard !Task.isCancelled else { return }
                self?.egressIP = ip
                guard let baseline = self?.preTunnelEgressIP else {
                    self?.egressVerified = false
                    self?.message = "IP خروجی دریافت شد، اما IP پیش از اتصال ثبت نشده بود؛ مسیر VPN مستقلاً تأیید نشد."
                    return
                }
                guard baseline != ip else {
                    self?.egressVerified = false
                    self?.message = "IP خروجی با IP پیش از اتصال یکسان است؛ عبور از تونل مستقلاً قابل تأیید نیست."
                    return
                }
                self?.egressVerified = true
                self?.message = "تغییر IP خروجی پس از اتصال تأیید شد: \(ip)"
            } catch {
                guard !Task.isCancelled else { return }
                self?.egressIP = nil
                self?.egressVerified = false
                self?.message = "تونل گزارش اتصال داده، اما عبور واقعی هنوز تأیید نشده است: \(error.localizedDescription)"
            }
        }
    }

    /// Fresh ephemeral HTTPS request with caches/cookies disabled.
    private static func fetchPublicEgressIP() async throws -> String {
        var components = URLComponents(string: "https://www.cloudflare.com/cdn-cgi/trace")!
        components.queryItems = [URLQueryItem(name: "nonce", value: UUID().uuidString)]
        var request = URLRequest(url: components.url!)
        request.httpMethod = "GET"
        request.timeoutInterval = 8
        request.cachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        request.setValue("no-cache, no-store", forHTTPHeaderField: "Cache-Control")
        request.setValue("close", forHTTPHeaderField: "Connection")

        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForResource = 10
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200,
              let body = String(data: data, encoding: .utf8) else {
            throw EgressVerificationError.invalidResponse
        }
        let fields = Dictionary(
            body.split(whereSeparator: \.isNewline).compactMap { line -> (String, String)? in
                let parts = line.split(separator: "=", maxSplits: 1)
                guard parts.count == 2 else { return nil }
                return (String(parts[0]), String(parts[1]))
            },
            uniquingKeysWith: { _, latest in latest }
        )
        guard let ip = fields["ip"],
              IPv4Address(ip) != nil || IPv6Address(ip) != nil else {
            throw EgressVerificationError.invalidResponse
        }
        return ip
    }

    var statusLabel: String {
        switch status {
        case .connected: return egressVerified ? "تونل متصل؛ IP خروجی تغییر کرده" : "تونل متصل؛ بررسی IP خروجی"
        case .connecting, .reasserting: return "در حال اتصال"
        case .disconnecting: return "در حال قطع"
        case .disconnected: return "قطع"
        case .invalid: return "پروفایل نصب نشده"
        @unknown default: return "نامشخص"
        }
    }

    private enum EgressVerificationError: LocalizedError {
        case invalidResponse
        case tunnelNotVerified

        var errorDescription: String? {
            switch self {
            case .invalidResponse: return "پاسخ سرویس بررسی IP معتبر نبود."
            case .tunnelNotVerified: return "IP خروجی معتبر یا نشانگر WARP فعال دریافت نشد."
            }
        }
    }
}
