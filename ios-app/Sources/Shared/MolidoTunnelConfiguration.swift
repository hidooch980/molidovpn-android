import Foundation
import Network

enum MolidoTunnelConfigurationError: LocalizedError {
    case invalidProviderProfile
    case sharedContainerUnavailable
    case bootstrapMissing
    case unsupportedVersion
    case invalidConfigurationFile
    case invalidDNS
    case invalidMTU
    case invalidTunnelAddress
    case unsupportedRoutePolicy
    case nativePacketAdapterNotReady

    var errorDescription: String? {
        switch self {
        case .invalidProviderProfile: return "Molido VPN profile is invalid or unsupported."
        case .sharedContainerUnavailable: return "Molido App Group container is unavailable; verify entitlements and provisioning."
        case .bootstrapMissing: return "Tunnel engine bootstrap has not been provisioned."
        case .unsupportedVersion: return "Tunnel bootstrap version is not supported."
        case .invalidConfigurationFile: return "Engine config filename is invalid or unsafe."
        case .invalidDNS: return "A configured DNS server address is invalid."
        case .invalidMTU: return "MTU is outside the supported safe range."
        case .invalidTunnelAddress: return "A native tunnel address is invalid."
        case .unsupportedRoutePolicy: return "This iOS build supports full-tunnel routing only; refusing a partial or leaking route policy."
        case .nativePacketAdapterNotReady: return "The native packet adapter is not ready."
        }
    }
}

/// Non-secret bootstrap shared by the app and Packet Tunnel extension.
/// The Aether core provisions and persists its own MASQUE/WireGuard identity
/// under the protected App Group engine directory.
struct MolidoTunnelConfiguration: Codable {
    static let currentVersion = 2
    static let appGroupIdentifier = "group.com.molido.meshai"
    static let fileName = "tunnel-bootstrap.json"

    enum EngineProtocol: String, Codable {
        case masque
        case wireguard
    }

    let version: Int
    let engineProtocol: EngineProtocol
    let engineConfigurationFile: String
    let dnsServers: [String]
    let mtu: Int
    let includeDefaultRoute: Bool

    func validate() throws {
        guard version == Self.currentVersion else { throw MolidoTunnelConfigurationError.unsupportedVersion }
        guard Self.isSafeFileName(engineConfigurationFile) else { throw MolidoTunnelConfigurationError.invalidConfigurationFile }
        guard (1280...9000).contains(mtu) else { throw MolidoTunnelConfigurationError.invalidMTU }
        guard !dnsServers.isEmpty,
              dnsServers.allSatisfy({ IPv4Address($0) != nil || IPv6Address($0) != nil }) else {
            throw MolidoTunnelConfigurationError.invalidDNS
        }
        // Split routing is not implemented in the iOS provider yet.
        guard includeDefaultRoute else { throw MolidoTunnelConfigurationError.unsupportedRoutePolicy }
    }

    static func load(from appGroup: String) throws -> MolidoTunnelConfiguration {
        guard let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else {
            throw MolidoTunnelConfigurationError.sharedContainerUnavailable
        }
        let url = root.appendingPathComponent(fileName, isDirectory: false)
        guard FileManager.default.fileExists(atPath: url.path) else { throw MolidoTunnelConfigurationError.bootstrapMissing }
        let configuration = try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
        try configuration.validate()
        return configuration
    }

    private static func isSafeFileName(_ value: String) -> Bool {
        !value.isEmpty && value != "." && value != ".." &&
        !value.contains("/") && !value.contains("\\") && !value.contains("\0")
    }
}
