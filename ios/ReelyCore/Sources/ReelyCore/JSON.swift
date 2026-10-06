import Foundation

/**
 * An answer as it came, without a shape decided for it in advance. Plex and the IPTV
 * panels say a number as a number one day and a string the next, and a flag as true, 1 or
 * "1": read through this, each is taken however it was sent, as the other apps take it.
 */
public enum JSON: Decodable, Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let n = try? c.decode(Double.self) { self = .number(n) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else if let a = try? c.decode([JSON].self) { self = .array(a) }
        else { self = .object(try c.decode([String: JSON].self)) }
    }

    public static func parse(_ data: Data) -> JSON? {
        try? JSONDecoder().decode(JSON.self, from: data)
    }

    public static func parse(_ text: String) -> JSON? {
        parse(Data(text.utf8))
    }

    public subscript(key: String) -> JSON {
        if case .object(let o) = self { return o[key] ?? .null }
        return .null
    }

    public subscript(index: Int) -> JSON {
        if case .array(let a) = self, a.indices.contains(index) { return a[index] }
        return .null
    }

    /// The list, or none; a single entry where a list was expected is one entry.
    public var array: [JSON] {
        if case .array(let a) = self { return a }
        return []
    }

    public var isArray: Bool { if case .array = self { return true }; return false }
    public var isObject: Bool { if case .object = self { return true }; return false }
    public var isNull: Bool { self == .null }

    /// As text, the way JavaScript's String() says it; "" for nothing.
    public var str: String {
        switch self {
        case .null: return ""
        case .bool(let b): return b ? "true" : "false"
        case .string(let s): return s
        case .number(let n):
            if n.rounded() == n, abs(n) < 1e15 { return String(Int64(n)) }
            return String(n)
        default: return ""
        }
    }

    /// Text, or nil when there's none.
    public var text: String? { let s = str; return s.isEmpty ? nil : s }

    /// As a number, the way JavaScript's Number() reads it; nil where that's NaN.
    public var number: Double? {
        switch self {
        case .null: return 0
        case .bool(let b): return b ? 1 : 0
        case .number(let n): return n
        case .string(let s):
            let t = s.trimmingCharacters(in: .whitespaces)
            if t.isEmpty { return 0 }
            return Double(t)
        default: return nil
        }
    }

    /// Number(x) || 0.
    public var num: Double { let n = number ?? 0; return n.isNaN ? 0 : n }
    public var int: Int { let n = num; return n.isFinite ? Int(n) : 0 }

    /// A positive whole number, or nil.
    public var positive: Int? { let n = num; return n > 0 && n.isFinite ? Int(n) : nil }

    /// A flag however it was said: true, 1 or "1", "true".
    public var flag: Bool {
        switch self {
        case .bool(let b): return b
        case .number(let n): return n == 1
        case .string(let s): return s == "1" || s.lowercased() == "true"
        default: return false
        }
    }

    /// Exactly true.
    public var isTrue: Bool { self == .bool(true) }
}
