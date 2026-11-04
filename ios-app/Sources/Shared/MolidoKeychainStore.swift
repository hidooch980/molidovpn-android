import Foundation
import Security

enum MolidoKeychainError: LocalizedError {
    case invalidReference
    case sharedAccessGroupUnavailable
    case operationFailed(OSStatus)

    var errorDescription: String? {
        switch self {
        case .invalidReference:
            return "The Keychain service or account reference is invalid."
        case .sharedAccessGroupUnavailable:
            return "The shared Keychain access group is not configured in this build."
        case .operationFailed(let status):
            return "Keychain operation failed (OSStatus \(status))."
        }
    }
}

/// Stores opaque credentials in the app/extension shared Keychain group.
/// Callers never receive or log credentials through providerConfiguration or
/// the App Group bootstrap document.
enum MolidoKeychainStore {
    private static let accessGroupInfoKey = "MolidoKeychainAccessGroup"

    static func save(
        _ secret: Data,
        service: String,
        account: String
    ) throws {
        let query = try baseQuery(service: service, account: account)
        let update: [String: Any] = [
            kSecValueData as String: secret,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]

        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecSuccess {
            return
        }
        guard status == errSecItemNotFound else {
            throw MolidoKeychainError.operationFailed(status)
        }

        var insert = query
        insert[kSecValueData as String] = secret
        insert[kSecAttrAccessible as String] =
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let insertStatus = SecItemAdd(insert as CFDictionary, nil)
        guard insertStatus == errSecSuccess else {
            throw MolidoKeychainError.operationFailed(insertStatus)
        }
    }

    static func load(service: String, account: String) throws -> Data {
        var query = try baseQuery(service: service, account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess else {
            throw MolidoKeychainError.operationFailed(status)
        }
        guard let data = result as? Data else {
            throw MolidoKeychainError.operationFailed(errSecDecode)
        }
        return data
    }

    static func delete(service: String, account: String) throws {
        let query = try baseQuery(service: service, account: account)
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw MolidoKeychainError.operationFailed(status)
        }
    }

    private static func baseQuery(
        service: String,
        account: String
    ) throws -> [String: Any] {
        guard isSafeReference(service), isSafeReference(account) else {
            throw MolidoKeychainError.invalidReference
        }
        guard
            let accessGroup = Bundle.main.object(
                forInfoDictionaryKey: accessGroupInfoKey
            ) as? String,
            !accessGroup.isEmpty,
            !accessGroup.contains("$(")
        else {
            throw MolidoKeychainError.sharedAccessGroupUnavailable
        }

        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: accessGroup
        ]
    }

    private static func isSafeReference(_ value: String) -> Bool {
        !value.isEmpty &&
        value.count <= 128 &&
        value.range(
            of: "^[A-Za-z0-9._-]+$",
            options: .regularExpression
        ) != nil
    }
}
