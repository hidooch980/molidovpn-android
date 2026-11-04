import Foundation

enum MolidoTunnelBootstrapStore {
    /// Atomically publishes a validated, non-secret bootstrap document.
    /// Engine credentials must be stored separately in the shared Keychain.
    @discardableResult
    static func save(
        _ configuration: MolidoTunnelConfiguration,
        to appGroup: String = MolidoTunnelConfiguration.appGroupIdentifier
    ) throws -> URL {
        try configuration.validate()

        guard let root = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroup
        ) else {
            throw MolidoTunnelConfigurationError.sharedContainerUnavailable
        }

        let destination = root.appendingPathComponent(
            MolidoTunnelConfiguration.fileName,
            isDirectory: false
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(configuration)

        try data.write(to: destination, options: [.atomic])
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: destination.path
        )
        return destination
    }

    /// Resolves only a filename already constrained by configuration validation.
    /// The provider must reject a missing engine file instead of starting a partial tunnel.
    static func engineConfigurationURL(
        for configuration: MolidoTunnelConfiguration,
        in appGroup: String = MolidoTunnelConfiguration.appGroupIdentifier
    ) throws -> URL {
        try configuration.validate()
        guard let root = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroup
        ) else {
            throw MolidoTunnelConfigurationError.sharedContainerUnavailable
        }

        let fileURL = root.appendingPathComponent(
            configuration.engineConfigurationFile,
            isDirectory: false
        )
        var isDirectory: ObjCBool = false
        guard FileManager.default.fileExists(
            atPath: fileURL.path,
            isDirectory: &isDirectory
        ), !isDirectory.boolValue else {
            throw MolidoTunnelConfigurationError.bootstrapMissing
        }
        return fileURL
    }
}
