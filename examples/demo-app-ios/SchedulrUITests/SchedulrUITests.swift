import XCTest

final class SchedulrUITests: XCTestCase {
    func testLaunch() throws {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 10))
    }
}
