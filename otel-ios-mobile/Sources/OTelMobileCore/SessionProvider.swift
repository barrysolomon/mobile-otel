import Foundation

public protocol SessionProvider: Sendable {
    var sessionId: String { get }
    func rotateSession() -> String
}
