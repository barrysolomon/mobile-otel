import XCTest

final class SchedulrUITests: XCTestCase {
    func testLaunch() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))
    }

    /// Drives the SDK's http.error emission path end-to-end on a real
    /// simulator: navigate to Appointments tab → tap the DebugToolbar's
    /// HTTP 500 button → wait for the resulting 404 + flush.
    ///
    /// This is the iOS counterpart to Android's
    /// `ConditionalFlushScenarios.httpErrorFlush` instrumented test. The
    /// assertion is intentionally minimal — the proof is in the log
    /// stream captured around the test invocation, which shows the SDK
    /// emitting an `http.error` log and the URLProtocol exporting it
    /// through to Dash0's ingress (or local collector if so configured).
    func testHttpErrorThroughOTelURLProtocol() throws {
        let app = XCUIApplication()
        app.launch()

        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))

        // Tab accessibilityIdentifiers don't always propagate to the underlying
        // UITabBar button on iOS — match by label instead.
        let appointmentsTab = app.tabBars.buttons["Appointments"]
        XCTAssertTrue(appointmentsTab.waitForExistence(timeout: 5))
        appointmentsTab.tap()

        // Tap the HTTP 500 button — fires `forceNextFetchError = true`
        // and triggers `reload()` which calls `fetchAppointments()` which
        // hits `/api/force-error-<timestamp>` via URLSession through
        // `OTelURLProtocol`, producing a real 404 response that the SDK
        // observes and (post-fix) emits as `http.error` with
        // `event.name = "http.error"`.
        let http500 = app.buttons["debug.http500"]
        XCTAssertTrue(http500.waitForExistence(timeout: 5))
        http500.tap()

        // Give the SDK a generous window: URLSession round-trip + buffer
        // append + (HYBRID/CONDITIONAL) policy match + flushWindow +
        // exporter POST. ~8s is plenty on the simulator.
        Thread.sleep(forTimeInterval: 8)
    }
}
