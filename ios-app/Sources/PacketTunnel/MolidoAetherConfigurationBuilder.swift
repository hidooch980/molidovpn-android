import Foundation
import Network
import NetworkExtension
import AetherFFI

enum MolidoAetherConfigurationError: LocalizedError {
    case nativePreparationFailed(String)
    case invalidNativeResult
    case invalidAssignedAddress
    case unsupportedRoutePolicy
    case invalidEngineOptions

    var errorDescription: String? {
        switch self {
        case .nativePreparationFailed(let message):
            return "Aether could not prepare the tunnel identity: \(message)"
        case .invalidNativeResult:
            return "Aether returned an invalid tunnel address response."
        case .invalidAssignedAddress:
            return "Aether returned an invalid IPv4 or IPv6 tunnel address."
        case .unsupportedRoutePolicy:
            return "The initial iOS release requires full-tunnel routing for IPv4 and IPv6."
        case .invalidEngineOptions:
            return "The native engine options could not be encoded."
        }
    }
}

struct MolidoAetherPreparedAddresses: Decodable {
    let ipv4CIDR: String
    let ipv6CIDR: String

    enum CodingKeys: String, CodingKey {
        case ipv4CIDR = "ipv4_cidr"
        case ipv6CIDR = "ipv6_cidr"
    }
}

/// Produces the exact NativeStartOptions JSON consumed by core/aether/src/ffi.rs.
/// Aether owns account provisioning and stores its identity beneath the protected
/// App Group engine directory; no invented generic Keychain credential is passed.
enum MolidoAetherConfigurationBuilder {
    static func makeOptionsJSON(
        for configuration: MolidoTunnelConfiguration,
        engineConfigurationURL: URL
    ) throws -> String {
        try configuration.validate()
        guard configuration.includeDefaultRoute else {
            throw MolidoAetherConfigurationError.unsupportedRoutePolicy
        }

        var options: [String: Any] = [
            "config_path": engineConfigurationURL.path,
            "protocol": configuration.engineProtocol.rawValue,
            "listen": "127.0.0.1:1819",
            "scan_mode": "balanced",
            "ip_scan": "v4",
            "endpoint_discovery": "cache",
            "masque_transport": "h3",
            "tls_curve_preset": "chrome",
            "wireguard_data_check": true,
            "retry_obfuscation_profiles": true,
            "log_level": "info",
            "dns_servers": configuration.dnsServers.joined(separator: ",")
        ]

        switch configuration.engineProtocol {
        case .masque:
            options["masque_config_path"] = engineConfigurationURL.path
        case .wireguard:
            options["wireguard_config_path"] = engineConfigurationURL.path
        }

        guard JSONSerialization.isValidJSONObject(options),
              let data = try? JSONSerialization.data(withJSONObject: options, options: [.sortedKeys]),
              let json = String(data: data, encoding: .utf8) else {
            throw MolidoAetherConfigurationError.invalidEngineOptions
        }
        return json
    }

    /// Blocking native preparation provisions/loads the selected identity and
    /// must only be called from a worker queue, never the NetworkExtension queue.
    static func prepare(optionsJSON: String) throws -> MolidoAetherPreparedAddresses {
        let code = optionsJSON.withCString { aether_prepare_json($0) }
        guard code == 0 else {
            let message = aether_last_error().map { String(cString: $0) } ?? "native error \(code)"
            throw MolidoAetherConfigurationError.nativePreparationFailed(message)
        }
        guard let result = aether_last_result() else {
            throw MolidoAetherConfigurationError.invalidNativeResult
        }
        let data = Data(String(cString: result).utf8)
        guard let addresses = try? JSONDecoder().decode(
            MolidoAetherPreparedAddresses.self,
            from: data
        ) else {
            throw MolidoAetherConfigurationError.invalidNativeResult
        }
        guard validCIDR(addresses.ipv4CIDR, ipv6: false),
              validCIDR(addresses.ipv6CIDR, ipv6: true) else {
            throw MolidoAetherConfigurationError.invalidAssignedAddress
        }
        return addresses
    }

    static func makeNetworkSettings(
        configuration: MolidoTunnelConfiguration,
        addresses: MolidoAetherPreparedAddresses
    ) throws -> NEPacketTunnelNetworkSettings {
        try configuration.validate()
        guard configuration.includeDefaultRoute,
              let ipv4 = parseCIDR(addresses.ipv4CIDR, ipv6: false),
              let ipv6 = parseCIDR(addresses.ipv6CIDR, ipv6: true) else {
            throw MolidoAetherConfigurationError.invalidAssignedAddress
        }

        // Aether selects its remote edge dynamically. The packet tunnel's
        // remoteAddress is a required NetworkExtension label, not the peer IP.
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "127.0.0.1")
        let ipv4Settings = NEIPv4Settings(
            addresses: [ipv4.address],
            subnetMasks: [ipv4SubnetMask(prefix: ipv4.prefix)]
        )
        ipv4Settings.includedRoutes = [NEIPv4Route.default()]
        let ipv6Settings = NEIPv6Settings(
            addresses: [ipv6.address],
            networkPrefixLengths: [NSNumber(value: ipv6.prefix)]
        )
        ipv6Settings.includedRoutes = [NEIPv6Route.default()]
        let dns = NEDNSSettings(servers: configuration.dnsServers)
        dns.matchDomains = [""]
        dns.matchDomainsNoSearch = true

        settings.ipv4Settings = ipv4Settings
        settings.ipv6Settings = ipv6Settings
        settings.dnsSettings = dns
        settings.mtu = NSNumber(value: configuration.mtu)
        return settings
    }

    private static func validCIDR(_ value: String, ipv6: Bool) -> Bool {
        parseCIDR(value, ipv6: ipv6) != nil
    }

    private static func parseCIDR(
        _ value: String,
        ipv6: Bool
    ) -> (address: String, prefix: Int)? {
        let parts = value.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, let prefix = Int(parts[1]) else { return nil }
        let address = String(parts[0])
        if ipv6 {
            guard IPv6Address(address) != nil, (0...128).contains(prefix) else { return nil }
        } else {
            guard IPv4Address(address) != nil, (0...32).contains(prefix) else { return nil }
        }
        return (address, prefix)
    }

    private static func ipv4SubnetMask(prefix: Int) -> String {
        let mask: UInt32 = prefix == 0 ? 0 : UInt32.max << (32 - prefix)
        return (0..<4).map { shift in
            String((mask >> (24 - (shift * 8))) & 0xff)
        }.joined(separator: ".")
    }
}
