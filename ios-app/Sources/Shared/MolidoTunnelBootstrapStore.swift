import Foundation

enum MolidoTunnelBootstrapStore {
    private static let engineDirectoryName = "engine"
    private static let protectedFileClass = FileProtectionType.completeUntilFirstUserAuthentication

    /// Atomically publishes a validated, non-secret bootstrap document.
    /// Engine credentials and Aether's persisted identity are protected separately.
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
            [.protectionKey: protectedFileClass, .posixPermissions: 0o600],
            ofItemAtPath: destination.path
        )
        return destination
    }

    /// Creates a private, Data-Protection-class directory before Aether starts.
    /// Rust persists MASQUE identity material (including certificates and tokens)
    /// below config_path, so protecting only the bootstrap JSON is insufficient.
    @discardableResult
    static func prepareEngineStorage(
        in appGroup: String = MolidoTunnelConfiguration.appGroupIdentifier
    ) throws -> URL {
        guard let root = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: appGroup
        ) else {
            throw MolidoTunnelConfigurationError.sharedContainerUnavailable
        }

        let directory = root.appendingPathComponent(
            engineDirectoryName,
            isDirectory: true
        )
        let attributes: [FileAttributeKey: Any] = [
            .protectionKey: protectedFileClass,
            .posixPermissions: 0o700
        ]
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: attributes
        )
        try FileManager.default.setAttributes(
            attributes,
            ofItemAtPath: directory.path
        )

        // Re-apply protection to existing identity/cache files on every launch.
        if let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey],
            options: []
        ) {
            for case let item as URL in enumerator {
                let values = try item.resourceValues(
                    forKeys: [.isDirectoryKey, .isSymbolicLinkKey]
                )
                guard values.isSymbolicLink != true else {
                    throw MolidoTunnelConfigurationError.invalidConfigurationFile
                }
                let mode: Int = values.isDirectory == true ? 0o700 : 0o600
                try FileManager.default.setAttributes(
                    [.protectionKey: protectedFileClass, .posixPermissions: mode],
                    ofItemAtPath: item.path
                )
            }
        }
        return directory
    }

    /// Resolves a validated engine filename inside the private engine directory.
    /// The provider rejects missing files and symlinks rather than starting partially.
    static func engineConfigurationURL(
        for configuration: MolidoTunnelConfiguration,
        in appGroup: String = MolidoTunnelConfiguration.appGroupIdentifier
    ) throws -> URL {
        try configuration.validate()
        let directory = try prepareEngineStorage(in: appGroup)
        let fileURL = directory.appendingPathComponent(
            configuration.engineConfigurationFile,
            isDirectory: false
        )
        var isDirectory: ObjCBool = false
        if FileManager.default.fileExists(atPath: fileURL.path, isDirectory: &isDirectory) {
            guard !isDirectory.boolValue else {
                throw MolidoTunnelConfigurationError.invalidConfigurationFile
            }
            let values = try fileURL.resourceValues(forKeys: [.isSymbolicLinkKey])
            guard values.isSymbolicLink != true else {
                throw MolidoTunnelConfigurationError.invalidConfigurationFile
            }
            try FileManager.default.setAttributes(
                [.protectionKey: protectedFileClass, .posixPermissions: 0o600],
                ofItemAtPath: fileURL.path
            )
        }
        // The native prepare call creates this identity file on first launch.
        // The containing engine directory is already private and data-protected.
        return fileURL
    }
}
