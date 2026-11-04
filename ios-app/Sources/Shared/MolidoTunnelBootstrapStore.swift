import Foundation

enum MolidoTunnelBootstrapStore {
    private static let engineDirectoryName = "engine"
    private static let protectedFileClass = FileProtectionType.completeUntilFirstUserAuthentication

    @discardableResult
    static func save(_ configuration: MolidoTunnelConfiguration, to appGroup: String = MolidoTunnelConfiguration.appGroupIdentifier) throws -> URL {
        try configuration.validate()
        guard let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else {
            throw MolidoTunnelConfigurationError.sharedContainerUnavailable
        }
        let destination = root.appendingPathComponent(MolidoTunnelConfiguration.fileName, isDirectory: false)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(configuration).write(to: destination, options: [.atomic])
        try FileManager.default.setAttributes(
            [.protectionKey: protectedFileClass, .posixPermissions: 0o600],
            ofItemAtPath: destination.path
        )
        return destination
    }

    /// Aether persists identity keys, tokens and certificates below this private directory.
    @discardableResult
    static func prepareEngineStorage(in appGroup: String = MolidoTunnelConfiguration.appGroupIdentifier) throws -> URL {
        guard let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else {
            throw MolidoTunnelConfigurationError.sharedContainerUnavailable
        }
        let directory = root.appendingPathComponent(engineDirectoryName, isDirectory: true)
        let attributes: [FileAttributeKey: Any] = [.protectionKey: protectedFileClass, .posixPermissions: 0o700]
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: attributes)
        try FileManager.default.setAttributes(attributes, ofItemAtPath: directory.path)

        if let enumerator = FileManager.default.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey],
            options: []
        ) {
            for case let item as URL in enumerator {
                let values = try item.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
                guard values.isSymbolicLink != true else { throw MolidoTunnelConfigurationError.invalidConfigurationFile }
                try FileManager.default.setAttributes(
                    [.protectionKey: protectedFileClass, .posixPermissions: values.isDirectory == true ? 0o700 : 0o600],
                    ofItemAtPath: item.path
                )
            }
        }
        return directory
    }

    /// The identity file may not exist on first launch; Aether creates it during prepare.
    static func engineConfigurationURL(
        for configuration: MolidoTunnelConfiguration,
        in appGroup: String = MolidoTunnelConfiguration.appGroupIdentifier
    ) throws -> URL {
        try configuration.validate()
        let directory = try prepareEngineStorage(in: appGroup)
        let fileURL = directory.appendingPathComponent(configuration.engineConfigurationFile, isDirectory: false)
        var isDirectory: ObjCBool = false
        if FileManager.default.fileExists(atPath: fileURL.path, isDirectory: &isDirectory) {
            guard !isDirectory.boolValue else { throw MolidoTunnelConfigurationError.invalidConfigurationFile }
            let values = try fileURL.resourceValues(forKeys: [.isSymbolicLinkKey])
            guard values.isSymbolicLink != true else { throw MolidoTunnelConfigurationError.invalidConfigurationFile }
            try FileManager.default.setAttributes(
                [.protectionKey: protectedFileClass, .posixPermissions: 0o600],
                ofItemAtPath: fileURL.path
            )
        }
        return fileURL
    }
}
