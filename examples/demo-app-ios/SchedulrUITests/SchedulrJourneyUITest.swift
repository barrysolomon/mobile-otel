import XCTest

/// UI-driven sustained load generator for the Schedulr iOS demo app.
///
/// Launches via `XCUIApplication`, then taps real SwiftUI controls
/// (looked up by `accessibilityIdentifier` / label) in a loop across all
/// four tabs. Every tap synthesises a real `UITouch` that flows through
/// SwiftUI's gesture system into the app's view models, which in turn
/// call into the OTel Mobile SDK (auto-instrumented taps/screens + the
/// app's own explicit spans/logs in `BookingView`/`AppointmentsView`).
/// This is the iOS analog of Android's `monkey` loop, and mirrors the
/// established pattern in
/// `AstronomyShopUITests/AstronomyShopJourneyUITest.swift` — no timers
/// or direct SDK calls bypass the UI; only real synthetic touches.
final class SchedulrJourneyUITest: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Diagnostic test — runs first (XCTest alphabetizes by default, and
    /// `test00...` sorts ahead of `testJourneyLoop`). If this fails the
    /// runner never attached correctly; dump the full accessibility tree
    /// so a broken identifier can be triaged without re-running the full
    /// sustained-load test.
    func test00AppLaunches() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-DASH0_UI_TEST"]
        app.launch()
        // SwiftUI + the SDK's auto-install need a beat to settle.
        Thread.sleep(forTimeInterval: 3)
        let state = app.state
        print("APP_STATE \(state.rawValue)")
        print("APP_TREE BEGIN\n\(app.debugDescription)\nAPP_TREE END")
        XCTAssert(
            state == .runningForeground,
            "App state is \(state.rawValue), expected runningForeground"
        )
    }

    func testJourneyLoop() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-DASH0_UI_TEST"]
        app.launch()
        // The tab bar's accessibility tree materialises async; queries
        // issued during the first frame often time out.
        Thread.sleep(forTimeInterval: 3)

        // Hard gate: the app must be running with its tab bar visible
        // before we start tapping.
        let tabBar = app.tabBars.firstMatch
        XCTAssert(
            tabBar.waitForExistence(timeout: 20),
            "Schedulr tab bar did not appear within 20s of launch."
        )

        // Budget: JOURNEY_DURATION_SECONDS env → default 75 (mirrors
        // AstronomyShopJourneyUITest's default).
        let envBudget = ProcessInfo.processInfo.environment["JOURNEY_DURATION_SECONDS"]
            .flatMap(TimeInterval.init)
        let budgetSeconds: TimeInterval = envBudget ?? 75
        let deadline = Date().addingTimeInterval(budgetSeconds)

        var journey = 0
        while Date() < deadline {
            journey += 1
            runOneJourney(app: app, iteration: journey)
        }
        XCTContext.runActivity(named: "Completed \(journey) journey iterations") { _ in }
        print("JOURNEY_LOOP_COMPLETE iterations=\(journey)")
    }

    /// One journey cycles all four tabs: Calendar (visit only) → Book
    /// (pick provider/type/slot, optionally fill notes, submit) →
    /// Appointments (reload, occasionally trigger the HTTP 500 debug
    /// button instead) → Buffer (refresh stats). Every step is defensive
    /// — a screen whose async data hasn't loaded yet is skipped rather
    /// than failing the whole run (AstronomyShop's soft-continue style).
    private func runOneJourney(app: XCUIApplication, iteration: Int) {
        visitCalendarTab(app: app, iteration: iteration)
        visitBookTab(app: app, iteration: iteration)
        visitAppointmentsTab(app: app, iteration: iteration)
        visitBufferTab(app: app, iteration: iteration)
        print("JOURNEY_ITERATION_COMPLETE iteration=\(iteration)")
    }

    // MARK: - Tabs

    private func visitCalendarTab(app: XCUIApplication, iteration: Int) {
        guard tapTab(app, label: "Calendar") else { return }
        // Graphical DatePicker is unreliable to manipulate in XCUITest —
        // just visiting the screen is enough to emit a screen-view.
        Thread.sleep(forTimeInterval: 0.5)
    }

    private func visitBookTab(app: XCUIApplication, iteration: Int) {
        guard tapTab(app, label: "Book") else { return }
        Thread.sleep(forTimeInterval: 0.5)

        // Provider picker: standard Picker in a Form renders as a
        // NavigationLink row. Options populate async from the network —
        // wait for the row to become hittable first.
        let providerRow = app.otherElements["booking.provider"].exists
            ? app.otherElements["booking.provider"]
            : app.cells["booking.provider"]
        let providerControl = firstExisting(
            app.buttons["booking.provider"],
            app.cells["booking.provider"],
            app.otherElements["booking.provider"],
            app.staticTexts["booking.provider"]
        )
        _ = providerRow
        if let provider = providerControl, provider.waitForExistence(timeout: 5), provider.isHittable {
            provider.tap()
            // A list of provider names appears; tap the first real cell
            // (skip a potential "Select…" placeholder at index 0 if more
            // than one cell exists).
            if pickFirstAvailableCell(app: app, timeout: 5) {
                popNavigationIfNeeded(app)
            }
        }

        // Appointment type: segmented control, iterate segments.
        let typeControl = app.segmentedControls["booking.type"]
        if typeControl.waitForExistence(timeout: 3), typeControl.isHittable {
            let buttons = typeControl.buttons
            let count = buttons.count
            if count > 0 {
                let index = iteration % count
                let segment = buttons.element(boundBy: index)
                if segment.isHittable { segment.tap() }
            }
        }

        // Slot picker: depends on provider selection having loaded slots.
        let slotControl = firstExisting(
            app.buttons["booking.slot"],
            app.cells["booking.slot"],
            app.otherElements["booking.slot"],
            app.staticTexts["booking.slot"]
        )
        if let slot = slotControl, slot.waitForExistence(timeout: 5), slot.isHittable {
            slot.tap()
            if pickFirstAvailableCell(app: app, timeout: 5) {
                popNavigationIfNeeded(app)
            }
        }

        // Notes: fill occasionally to vary payloads.
        if iteration % 3 == 0 {
            let notes = app.textFields["booking.notes"]
            if notes.waitForExistence(timeout: 3), notes.isHittable {
                notes.tap()
                notes.typeText("Synthetic load iteration \(iteration)")
                app.keyboards.buttons["Return"].firstMatch.tap()
                if app.keyboards.buttons["Return"].exists == false {
                    // keyboard dismissed via return; nothing else to do
                }
            }
        }

        // Submit if enabled (requires provider + slot selected).
        let submit = app.buttons["booking.submit"]
        if submit.waitForExistence(timeout: 3), submit.isHittable, submit.isEnabled {
            submit.tap()
            Thread.sleep(forTimeInterval: 1)
        }
    }

    private func visitAppointmentsTab(app: XCUIApplication, iteration: Int) {
        // Tab bar identifiers don't always propagate to the underlying
        // UITabBar button on iOS — match by label (per SchedulrUITests).
        guard tapTabByLabel(app, label: "Appointments") else { return }
        Thread.sleep(forTimeInterval: 0.5)

        // No synthetic "debug.http500" tap here on purpose — real network
        // errors are injected externally (the demo-backend is stopped for
        // a window periodically), so the app hits a genuine
        // ECONNREFUSED/timeout through its real network stack instead of
        // a fabricated debug response.
        let reload = app.buttons["appointments.reload"]
        if reload.waitForExistence(timeout: 5), reload.isHittable {
            reload.tap()
            Thread.sleep(forTimeInterval: 0.5)
        }
    }

    private func visitBufferTab(app: XCUIApplication, iteration: Int) {
        guard tapTab(app, label: "Buffer") else { return }
        Thread.sleep(forTimeInterval: 0.5)

        // NEVER tap "buffer.crash" here — it calls fatalError() and would
        // kill the app, defeating sustained load generation.
        let refresh = app.buttons["buffer.refresh"]
        if refresh.waitForExistence(timeout: 5), refresh.isHittable {
            refresh.tap()
        }
    }

    // MARK: - Helpers

    /// Tap a tab by its accessibilityIdentifier first, falling back to
    /// label match (tab bar identifiers don't always propagate to the
    /// underlying UITabBar button on iOS).
    private func tapTab(_ app: XCUIApplication, label: String) -> Bool {
        let identifier = "tab.\(label.lowercased())"
        let byId = app.tabBars.buttons[identifier]
        if byId.waitForExistence(timeout: 5), byId.isHittable {
            byId.tap()
            return true
        }
        return tapTabByLabel(app, label: label)
    }

    private func tapTabByLabel(_ app: XCUIApplication, label: String) -> Bool {
        let byLabel = app.tabBars.buttons[label]
        guard byLabel.waitForExistence(timeout: 5), byLabel.isHittable else { return false }
        byLabel.tap()
        return true
    }

    /// Returns the first existing element among the given candidates.
    private func firstExisting(_ elements: XCUIElement...) -> XCUIElement? {
        for element in elements where element.exists {
            return element
        }
        return nil
    }

    /// After tapping a Form Picker's NavigationLink row, a list of
    /// options appears. Tap the last available cell (skips a potential
    /// "Select…" placeholder that's usually first) if any exist.
    @discardableResult
    private func pickFirstAvailableCell(app: XCUIApplication, timeout: TimeInterval) -> Bool {
        let cells = app.cells
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let count = cells.count
            if count > 0 {
                // Prefer the last cell — index 0 is often a "Select…"
                // placeholder with no real value.
                let target = cells.element(boundBy: count - 1)
                if target.isHittable {
                    target.tap()
                    return true
                }
            }
            Thread.sleep(forTimeInterval: 0.2)
        }
        return false
    }

    /// Standard-style Pickers in a Form auto-pop back after selection on
    /// most iOS versions, but guard in case a back button is still
    /// present (defensive; never trip over an app.navigationBars miss).
    private func popNavigationIfNeeded(_ app: XCUIApplication) {
        let navBars = app.navigationBars
        guard navBars.count > 0 else { return }
        let backButton = navBars.firstMatch.buttons.element(boundBy: 0)
        if backButton.exists && backButton.isHittable && backButton.label.lowercased().contains("book") {
            backButton.tap()
        }
    }
}
