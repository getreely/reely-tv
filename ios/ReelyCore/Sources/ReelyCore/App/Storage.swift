import Foundation

/** Where things are kept between runs: preferences in the open, tokens locked away. */
public protocol KeyValueStore: AnyObject, Sendable {
    func string(_ key: String) -> String?
    func set(_ key: String, _ value: String?)
}

public extension KeyValueStore {
    func json<T: Decodable>(_ key: String, as type: T.Type) -> T? {
        guard let text = string(key) else { return nil }
        return try? JSONDecoder().decode(T.self, from: Data(text.utf8))
    }

    func setJson<T: Encodable>(_ key: String, _ value: T?) {
        guard let value, let data = try? JSONEncoder().encode(value) else { set(key, nil); return }
        set(key, String(decoding: data, as: UTF8.self))
    }
}

/** Kept in memory only: for tests, and as a fallback. */
public final class MemoryStore: KeyValueStore, @unchecked Sendable {
    private var values: [String: String]
    private let lock = NSLock()
    public init(_ values: [String: String] = [:]) { self.values = values }
    public func string(_ key: String) -> String? { lock.lock(); defer { lock.unlock() }; return values[key] }
    public func set(_ key: String, _ value: String?) { lock.lock(); values[key] = value; lock.unlock() }
}

/** UserDefaults, for preferences. */
public final class DefaultsStore: KeyValueStore, @unchecked Sendable {
    private let defaults: UserDefaults
    public init(_ defaults: UserDefaults = .standard) { self.defaults = defaults }
    public func string(_ key: String) -> String? { defaults.string(forKey: key) }
    public func set(_ key: String, _ value: String?) {
        if let value { defaults.set(value, forKey: key) } else { defaults.removeObject(forKey: key) }
    }
}

#if canImport(Security)
import Security

/** The Keychain, for tokens and passwords: what the Fire TV keeps in its SecureStore. */
public final class KeychainStore: KeyValueStore, @unchecked Sendable {
    private let service: String
    public init(service: String = "tv.reely.secure") { self.service = service }

    private func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: key]
    }

    public func string(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    public func set(_ key: String, _ value: String?) {
        SecItemDelete(query(key) as CFDictionary)
        guard let value else { return }
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(q as CFDictionary, nil)
    }
}
#endif
