import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/** Something went wrong asking a server: a sentence somebody can read, and the status kept. */
public struct HttpError: Error, LocalizedError, Equatable, Sendable {
    public let message: String
    public let status: Int
    public init(_ message: String, status: Int = 0) {
        self.message = message
        self.status = status
    }
    public var errorDescription: String? { message }
}

/** One request, as the apps make them. */
public struct HttpRequest: Sendable {
    public var url: String
    public var method: String = "GET"
    public var headers: [String: String] = [:]
    public var body: Data? = nil
    public var timeout: TimeInterval = 20

    public init(url: String, method: String = "GET", headers: [String: String] = [:], body: Data? = nil, timeout: TimeInterval = 20) {
        self.url = url
        self.method = method
        self.headers = headers
        self.body = body
        self.timeout = timeout
    }
}

public struct HttpResponse: Sendable {
    public let status: Int
    public let data: Data
    public var ok: Bool { (200..<300).contains(status) }
    public var text: String { String(decoding: data, as: UTF8.self) }
    public init(status: Int, data: Data) {
        self.status = status
        self.data = data
    }
}

/** Whatever carries requests: the network, or a test's stand-in. */
public protocol HttpTransport: Sendable {
    func send(_ request: HttpRequest) async throws -> HttpResponse
}

public struct URLSessionTransport: HttpTransport {
    public init() {}
    public func send(_ request: HttpRequest) async throws -> HttpResponse {
        guard let url = URL(string: request.url) else { throw HttpError("That address isn't right.") }
        var r = URLRequest(url: url, timeoutInterval: request.timeout)
        r.httpMethod = request.method
        r.httpBody = request.body
        for (k, v) in request.headers { r.setValue(v, forHTTPHeaderField: k) }
        let (data, response) = try await URLSession.shared.data(for: r)
        return HttpResponse(status: (response as? HTTPURLResponse)?.statusCode ?? 0, data: data)
    }
}

/** Asking servers things, with errors as sentences, as the other apps do. */
public struct Http: Sendable {
    public var transport: HttpTransport
    public init(transport: HttpTransport = URLSessionTransport()) { self.transport = transport }

    public func ask(_ request: HttpRequest, failure: String? = nil) async throws -> HttpResponse {
        let response: HttpResponse
        do {
            response = try await transport.send(request)
        } catch let e as HttpError {
            throw e
        } catch {
            throw HttpError(failure ?? "Couldn't connect. Check the network and try again.")
        }
        guard response.ok else { throw HttpError(failure ?? "That didn't work. Try again.", status: response.status) }
        return response
    }

    public func json(_ request: HttpRequest, failure: String? = nil) async throws -> JSON {
        let response = try await ask(request, failure: failure)
        guard let json = JSON.parse(response.data) else {
            throw HttpError(failure ?? "The answer didn't make sense. Try again.", status: response.status)
        }
        return json
    }
}

/** encodeURIComponent, as the other apps escape a value in an address. */
public func encodeComponent(_ s: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-_.!~*'()")
    // Letters only from ASCII: alphanumerics takes in accented letters too.
    return s.unicodeScalars.map { scalar -> String in
        if scalar.isASCII, allowed.contains(scalar) { return String(scalar) }
        return String(scalar).utf8.map { String(format: "%%%02X", $0) }.joined()
    }.joined()
}
