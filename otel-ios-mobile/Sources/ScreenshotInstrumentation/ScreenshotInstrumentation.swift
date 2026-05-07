import Foundation
import OpenTelemetryApi
import OTelMobileCore
#if canImport(UIKit)
import UIKit
#endif

public final class ScreenshotInstrumentation: @unchecked Sendable {
    public static let shared = ScreenshotInstrumentation()

    public let config: ScreenshotConfig

    private let lock = NSLock()
    private var installed = false
    private var logger: Logger?
    private var tracer: Tracer?
    private var sessionProvider: SessionProvider?
    private var rateLimiter: RateLimiter
    private var sequenceNumber: Int64 = 0

    #if canImport(UIKit) && (os(iOS) || os(tvOS))
    private var observers: [NSObjectProtocol] = []
    #endif

    public init(config: ScreenshotConfig = ScreenshotConfig()) {
        self.config = config
        self.rateLimiter = RateLimiter(maxPerWindow: config.maxCapturesPerMinute)
    }

    public func install(context: InstrumentationContext) {
        lock.lock()
        if installed || !config.enabled {
            lock.unlock()
            return
        }
        installed = true
        self.logger = context.logger
        self.tracer = context.tracer
        self.sessionProvider = context.sessionProvider
        lock.unlock()

        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        if config.captureOnScreenView {
            let nc = NotificationCenter.default
            lock.lock()
            observers.append(nc.addObserver(
                forName: UIScene.didActivateNotification,
                object: nil, queue: .main
            ) { [weak self] _ in
                guard let self = self else { return }
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(self.config.screenViewDelayMs)) {
                    self.capture(trigger: "screen_view")
                }
            })
            lock.unlock()
        }
        #endif
    }

    public func uninstall() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        let nc = NotificationCenter.default
        for o in observers { nc.removeObserver(o) }
        observers.removeAll()
        #endif
        logger = nil
        tracer = nil
        sessionProvider = nil
        rateLimiter.reset()
    }

    public func capture(trigger: String = "manual") {
        guard config.enabled else { return }
        guard rateLimiter.tryAcquire() else { return }

        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        captureFromKeyWindow(trigger: trigger)
        #endif
    }

    #if canImport(UIKit) && (os(iOS) || os(tvOS))
    private func captureFromKeyWindow(trigger: String) {
        guard let window = Self.findKeyWindow() else { return }
        let renderer = UIGraphicsImageRenderer(size: window.bounds.size)
        let image = renderer.image { ctx in
            window.layer.render(in: ctx.cgContext)
        }

        guard let scaled = scaleImage(image) else { return }

        var finalImage = scaled
        if config.redactTextFields {
            finalImage = redactTextFields(in: window, image: scaled, originalSize: window.bounds.size)
        }

        guard let data = compressImage(finalImage) else { return }

        let payloadKb = data.count / 1024
        if payloadKb > config.maxPayloadKb { return }

        let base64 = data.base64EncodedString()
        let mimeType = config.format == .jpeg ? "image/jpeg" : "image/png"
        let dataUrl = "data:\(mimeType);base64,\(base64)"

        emitScreenshot(dataUrl: dataUrl, trigger: trigger, width: Int(scaled.size.width), height: Int(scaled.size.height), sizeBytes: data.count)
    }

    private static func findKeyWindow() -> UIWindow? {
        if #available(iOS 15.0, *) {
            return UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap { $0.windows }
                .first { $0.isKeyWindow }
        }
        return nil
    }

    internal func scaleImage(_ image: UIImage) -> UIImage? {
        let w = image.size.width
        let h = image.size.height
        let maxW = CGFloat(config.maxWidthPx)
        let maxH = CGFloat(config.maxHeightPx)

        if w <= maxW && h <= maxH { return image }

        let scaleW = maxW / w
        let scaleH = maxH / h
        let scale = min(scaleW, scaleH)

        let newSize = CGSize(width: (w * scale).rounded(), height: (h * scale).rounded())
        let renderer = UIGraphicsImageRenderer(size: newSize)
        return renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: newSize))
        }
    }

    internal func redactTextFields(in view: UIView, image: UIImage, originalSize: CGSize) -> UIImage {
        let rects = collectTextFieldRects(in: view)
        if rects.isEmpty { return image }

        let renderer = UIGraphicsImageRenderer(size: image.size)
        return renderer.image { ctx in
            image.draw(at: .zero)
            ctx.cgContext.setFillColor(UIColor.darkGray.cgColor)
            let scaleX = image.size.width / originalSize.width
            let scaleY = image.size.height / originalSize.height
            for rect in rects {
                let scaled = CGRect(
                    x: rect.origin.x * scaleX,
                    y: rect.origin.y * scaleY,
                    width: rect.width * scaleX,
                    height: rect.height * scaleY
                )
                ctx.cgContext.fill(scaled)
            }
        }
    }

    private func collectTextFieldRects(in view: UIView) -> [CGRect] {
        var rects: [CGRect] = []
        collectTextFieldRectsRecursive(view: view, rootView: view, rects: &rects)
        return rects
    }

    private func collectTextFieldRectsRecursive(view: UIView, rootView: UIView, rects: inout [CGRect]) {
        guard view.isHidden == false else { return }

        if view is UITextField || view is UITextView {
            let frame = view.convert(view.bounds, to: rootView)
            rects.append(frame)
        }

        for sub in view.subviews {
            collectTextFieldRectsRecursive(view: sub, rootView: rootView, rects: &rects)
        }
    }

    private func compressImage(_ image: UIImage) -> Data? {
        switch config.format {
        case .jpeg:
            return image.jpegData(compressionQuality: CGFloat(config.quality) / 100.0)
        case .png:
            return image.pngData()
        }
    }
    #endif

    private func emitScreenshot(dataUrl: String, trigger: String, width: Int, height: Int, sizeBytes: Int) {
        lock.lock()
        let logger = self.logger
        let session = self.sessionProvider
        let seq = sequenceNumber
        sequenceNumber += 1
        lock.unlock()

        guard let logger = logger else { return }

        var attrs: [String: AttributeValue] = [
            "mobile.screenshot.trigger": .string(trigger),
            "mobile.screenshot.format": .string(config.format.rawValue),
            "mobile.screenshot.width": .int(width),
            "mobile.screenshot.height": .int(height),
            "mobile.screenshot.size_bytes": .int(sizeBytes),
            "mobile.screenshot.redacted": .bool(config.redactTextFields),
            "mobile.screenshot.data_url": .string(dataUrl),
            "mobile.screenshot.sequence": .int(Int(seq)),
        ]
        if let sid = session?.sessionId {
            attrs["mobile.session.id"] = .string(sid)
        }

        logger.logRecordBuilder()
            .setBody(AttributeValue.string("ui.screenshot"))
            .setSeverity(.info)
            .setAttributes(attrs)
            .emit()
    }

    // MARK: - Test seam

    internal func emitForTesting(trigger: String, screenName: String) {
        lock.lock()
        let logger = self.logger
        let session = self.sessionProvider
        let seq = sequenceNumber
        sequenceNumber += 1
        lock.unlock()

        guard let logger = logger else { return }

        var attrs: [String: AttributeValue] = [
            "mobile.screenshot.trigger": .string(trigger),
            "screen.name": .string(screenName),
            "mobile.screenshot.sequence": .int(Int(seq)),
        ]
        if let sid = session?.sessionId {
            attrs["mobile.session.id"] = .string(sid)
        }

        logger.logRecordBuilder()
            .setBody(AttributeValue.string("ui.screenshot"))
            .setSeverity(.info)
            .setAttributes(attrs)
            .emit()
    }

    internal var isInstalled: Bool {
        lock.lock(); defer { lock.unlock() }
        return installed
    }
}
