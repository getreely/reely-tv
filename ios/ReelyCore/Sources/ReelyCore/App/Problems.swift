import Foundation

/**
 * What went wrong, kept on the device for Settings to show, as the Fire TV keeps its
 * problem report. Only the last one; nothing is sent anywhere.
 */
public struct Problem: Codable, Equatable, Sendable {
    /// Epoch seconds.
    public var at: Int
    public var message: String
    public var detail: String?
}

extension ReelyStore {
    private static let problemKey = "problem"
    /// Set while the app is open in front, cleared as it goes away: still set at the next
    /// launch, it ended while open without being closed.
    private static let openKey = "openNow"

    public var lastProblem: Problem? { store.json(Self.problemKey, as: Problem.self) }

    public func recordProblem(_ message: String, detail: String? = nil, now: Int = Int(Date().timeIntervalSince1970)) {
        let kept = Problem(at: now, message: String(message.prefix(500)), detail: detail.map { String($0.prefix(8000)) })
        store.setJson(Self.problemKey, kept)
    }

    public func clearProblem() { store.set(Self.problemKey, nil) }

    /**
     * As the app starts. Still open from last time, it ended without being closed: a crash,
     * or the system ending it for memory while it was in front. In the background the
     * system ends apps all the time, which is no problem, and isn't counted.
     */
    public func noteLaunched(now: Int = Int(Date().timeIntervalSince1970)) {
        if store.string(Self.openKey) == "1" {
            recordProblem("Reely closed unexpectedly",
                          detail: "Reely was open and stopped without being closed: it crashed, or the system ended it, most often to free memory.",
                          now: now)
        }
        store.set(Self.openKey, "1")
    }

    /// Out of sight: ending now is the system's business, not a problem.
    public func noteAway() { store.set(Self.openKey, "0") }
    public func noteBack() { store.set(Self.openKey, "1") }
}
