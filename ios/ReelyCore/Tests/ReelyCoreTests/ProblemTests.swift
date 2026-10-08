import XCTest
@testable import ReelyCore

/// The problem report: kept on the device, the last one only, and an unexpected end noticed at the next launch.
@MainActor
final class ProblemTests: XCTestCase {
    func testKeepsTheLastProblemUntilCleared() {
        let store = makeStore(FakePlex())
        XCTAssertNil(store.lastProblem)
        store.recordProblem("First", now: 10)
        store.recordProblem("Second", detail: "where", now: 20)
        XCTAssertEqual(store.lastProblem, Problem(at: 20, message: "Second", detail: "where"))
        store.clearProblem()
        XCTAssertNil(store.lastProblem)
    }

    func testAnEndWhileOpenIsNoticedAtTheNextLaunchButNotOneInTheBackground() {
        let store = makeStore(FakePlex())
        store.noteLaunched(now: 1)
        XCTAssertNil(store.lastProblem)
        store.noteAway()
        store.noteLaunched(now: 2)
        XCTAssertNil(store.lastProblem)
        // Open, and never closed: the next launch says so.
        store.noteLaunched(now: 3)
        XCTAssertEqual(store.lastProblem?.message, "Reely closed unexpectedly")
        XCTAssertEqual(store.lastProblem?.at, 3)
    }
}
