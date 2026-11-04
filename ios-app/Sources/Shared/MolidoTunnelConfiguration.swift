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
    case nativePacketAdapterNotReady

    var errorDescription: String? {
        switch self {
        case .invalidProviderProfile:
            return "Molido VPN profile is invalid or unsupported."
        case .sharedContainerUnavailable:
            return "Molido App Group container is unavailable; verify entitlements and provisioning."
        case .bootstrapMissing:
            return "Tunnel engine bootstrap has not been provisioned."
        case .unsupportedVersion:
            return "Tunnel bootstrap version is not supported."
        case .invalidConfigurationFile:
            return "Engine config filename or optional Keychain reference is invalid."
        case .invalidDNS:
            return "A configured DNS server address is invalid."
        case .invalidMTU:
            return "MTU is outside the supported safe range."
        case .invalidTunnelAddress:
            return "A tunnel interface CIDR address is invalid."
        case .nativePacketAdapterNotReady:
            return "Bootstrap is valid, but the iOS native packet adapter is not ready."
        }
    }
}

/// Versioned, non-secret bootstrap shared by the app and Packet Tunnel extension.
/// The native engine stores its provisioned identity in the protected engine directory. Optional Keychain references are metadata only; secrets never enter this document.
struct MolidoTunnelConfiguration: Codable {
    static let currentVersion = 1
    static let appGroupIdentifier = "group.com.molido.meshai"
    static let fileName = "tunnel-bootstrap.json"

    enum EngineProtocol: String, Codable {
        case masque
        case wireguard
    }

    let version: Int
    let engineProtocol: EngineProtocol
    let engineConfigurationFile: String
    let keychainService: String?
    let keychainAccount: String?
    let ipv4CIDR: String?
    let ipv6CIDR: String?
    let dnsServers: [String]
    let mtu: Int
    let includeDefaultRoute: Bool

    func validate() throws {
        guard version == Self.currentVersion else {
            throw MolidoTunnelConfigurationError.unsupportedVersion
        }
        guard Self.isSafeFileName(engineConfigurationFile),
              ((keychainService == nil && keychainAccount == nil) ||
               (keychainService.map(Self.isSafeIdentifier) == true &&
                keychainAccount.map(Self.isSafeIdentifier) == true)) else {
            throw MolidoTunnelConfigurationError.invalidConfigurationFile
        }
        guard (1280...9000).contains(mtu) else {
            throw MolidoTunnelConfigurationError.invalidMTU
        }
        guard includeDefaultRoute else {
            throw MolidoTunnelConfigurationError.invalidProviderProfile
        }
        guard !dnsServers.isEmpty,
              dnsServers.allSatisfy({ IPv4Address($0) != nil || IPv6Address($0) != nil }) else {
            throw MolidoTunnelConfigurationError.invalidDNS
        }
        if let ipv4CIDR, !Self.isValidCIDR(ipv4CIDR, ipv6: false) {
            throw MolidoTunnelConfigurationError.invalidTunnelAddress
        }
        if let ipv6CIDR, !Self.isValidCIDR(ipv6CIDR, ipv6: true) {
            throw MolidoTunnelConfigurationError.invalidTunnelAddress
        }
    }

    static func load(from appGroup: String) throws -> MolidoTunnelConfiguration {
        guard let root = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroup
        ) else {
            throw MolidoTunnelConfigurationError.sharedContainerUnavailable
        }
        let url = root.appendingPathComponent(fileName, isDirectory: false)
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw MolidoTunnelConfigurationError.bootstrapMissing
        }
        let data = try Data(contentsOf: url)
        let configuration = try JSONDecoder().decode(
            MolidoTunnelConfiguration.self,
            from: data
        )
        try configuration.validate()
        return configuration
    }

    private static func isSafeFileName(_ value: String) -> Bool {
        !value.isEmpty &&
        value != "." &&
        value != ".." &&
        !value.contains("/") &&
        !value.contains("\\") &&
        !value.contains("\0")
    }

    private static func isSafeIdentifier(_ value: String) -> Bool {
        !value.isEmpty &&
        value.count <= 128 &&
        value.range(of: "^[A-Za-z0-9._-]+$", options: .regularExpression) != nil
    }

    private static func isValidCIDR(_ value: String, ipv6: Bool) -> Bool {
        let parts = value.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, let prefix = Int(parts[1]) else {
            return false
        }
        let address = String(parts[0])
        if ipv6 {
            return IPv6Address(address) != nil && (0...128).contains(prefix)
        }
        return IPv4Address(address) != nil && (0...32).contains(prefix)
    }
}
