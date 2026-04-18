import XCTest

/// UI-driven demo journey for the Astronomy Shop iOS app.
///
/// Launches the app via `XCUIApplication`, then taps real SwiftUI
/// buttons (looked up by `accessibilityIdentifier`) in a loop. Every tap
/// synthesises a `UITouch` event that goes through SwiftUI's gesture
/// system → button closure → ViewModel mutation → `@Published` fire →
/// re-render → `ShopTelemetry` emission. This is the iOS analog of
/// Android's `monkey` loop in `run-dual-platform-demo.sh`.
final class AstronomyShopJourneyUITest: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    /// Diagnostic test — runs first in the suite. If this fails, the
    /// xctest runner isn't attached to the app correctly and no amount
    /// of query tuning will fix the real test. We run it briefly (no
    /// loops) and dump the app state + accessibility tree for triage.
    func test00AppLaunches() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-DASH0_UI_TEST"]
        app.launch()
        // Give SwiftUI + the SDK auto-install a beat to settle.
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
        // Give the app a beat. SwiftUI's accessibility tree materialises
        // async, so queries issued during the first frame often time out.
        Thread.sleep(forTimeInterval: 3)

        // Hard gate: the app must actually be running + on the product
        // list screen before we start tapping. The nav title is a stable
        // sentinel (`"Astronomy Shop"` is set by `.navigationTitle` in
        // `ProductListView`).
        let title = app.navigationBars["Astronomy Shop"]
        XCTAssert(
            title.waitForExistence(timeout: 20),
            "Astronomy Shop navigation bar did not appear within 20s of launch."
        )

        // Budget: JOURNEY_DURATION_SECONDS env → default 75.
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
    }

    /// One journey = open 3 products (tap row → tap add-to-cart → back) +
    /// go to cart + checkout.
    private func runOneJourney(app: XCUIApplication, iteration: Int) {
        // SwiftUI's `List` row wrapped in `NavigationLink` surfaces as a
        // cell on iOS 16/17 and as a button on iOS 18+. Query both; the
        // first matching element wins. We search by prefix since each row
        // has a per-product `accessibilityIdentifier("product.row.<id>")`.
        for tapIndex in 0..<3 {
            let row = firstProductRow(in: app)
            guard row.waitForExistence(timeout: 5), row.isHittable else {
                XCTFail("product row not hittable on iteration \(iteration)")
                return
            }
            row.tap()

            let add = app.buttons["product.add_to_cart"]
            if add.waitForExistence(timeout: 5), add.isHittable {
                add.tap()
            }
            popNavigation(app)
            _ = tapIndex  // silence unused
        }

        let cart = app.buttons["nav.cart"]
        if cart.waitForExistence(timeout: 5), cart.isHittable { cart.tap() }

        let checkout = app.buttons["cart.checkout"]
        if checkout.waitForExistence(timeout: 5), checkout.isHittable {
            checkout.tap()
            let ok = app.alerts.buttons.firstMatch
            if ok.waitForExistence(timeout: 6) { ok.tap() }
        }
        popNavigation(app)
    }

    /// Find the first product row. We first try `cells`, then `buttons`
    /// (iOS 18+ path), then fall back to any descendant. Each individual
    /// query has its own snapshot — if one is slow, the next is still
    /// cheap.
    private func firstProductRow(in app: XCUIApplication) -> XCUIElement {
        let cell = app.cells.element(matching: NSPredicate(format: "identifier BEGINSWITH %@", "product.row."))
        if cell.exists { return cell }
        let button = app.buttons.element(matching: NSPredicate(format: "identifier BEGINSWITH %@", "product.row."))
        if button.exists { return button }
        return app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "product.row."))
            .firstMatch
    }

    /// NavigationStack's back button is the first nav-bar button. This
    /// guard avoids tripping a query when we're already on the root
    /// screen (where the first nav-bar button is "cart", not "back").
    private func popNavigation(_ app: XCUIApplication) {
        let navBars = app.navigationBars
        guard navBars.count > 0 else { return }
        let firstButton = navBars.firstMatch.buttons.firstMatch
        // Don't tap the cart icon thinking it's a back button — the back
        // button has an empty identifier (SwiftUI default), the cart one
        // has identifier "nav.cart".
        if firstButton.exists && firstButton.identifier != "nav.cart" {
            firstButton.tap()
        }
    }
}
