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
    // Off-main queue for screenshot post-processing (scale / redaction
    // compositing / PNG-or-JPEG encode / base64). The window-layer render
    // MUST stay on main (UIKit), but the CPU-heavy encoding does not touch
    // UIView state and would otherwise hitch the main thread / trip the
    // 0x8BADF00D watchdog under error bursts. See captureFromKeyWindow.
    private let postProcessQueue = DispatchQueue(
        label: "com.dash0.screenshot.postprocess", qos: .utility
    )
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
        // Trigger-specific gates: policy-match captures default-on but
        // configurable via ScreenshotConfig.captureOnPolicyMatch. Mirrors the
        // Android-side gate in `ScreenshotInstrumentation.captureScreenshot`.
        if trigger.hasPrefix("policy_") && !config.captureOnPolicyMatch {
            return
        }
        guard rateLimiter.tryAcquire() else { return }

        #if canImport(UIKit) && (os(iOS) || os(tvOS))
        // captureFromKeyWindow touches UIApplication/UIWindow/CALayer, which
        // are main-thread-only. The screen_view trigger already arrives on
        // main (NotificationCenter queue: .main), but the error / policy-match
        // / public-API paths can call capture() from a background
        // Task.detached (see MobileLogRecordProcessor). Hop to main for those
        // to avoid off-main UIKit access (undefined behavior / host crash).
        if Thread.isMainThread {
            captureFromKeyWindow(trigger: trigger)
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.captureFromKeyWindow(trigger: trigger)
            }
        }
        #endif
    }

    #if canImport(UIKit) && (os(iOS) || os(tvOS))
    private func captureFromKeyWindow(trigger: String) {
        // MAIN THREAD ONLY below this point until the postProcessQueue hop:
        // UIApplication/UIWindow/UIView/CALayer access requires the main
        // thread. capture(trigger:) guarantees we're on main here.
        guard let window = Self.findKeyWindow() else { return }
        let originalSize = window.bounds.size
        let renderer = UIGraphicsImageRenderer(size: originalSize)
        let image = renderer.image { ctx in
            window.layer.render(in: ctx.cgContext)
        }

        // Collect redaction rects while still on main — this walks the live
        // UIView tree (UIView access). Everything after this hop touches only
        // the already-rendered UIImage and plain value types, so it is safe
        // off-main.
        let redactRects: [CGRect] = config.redactTextFields
            ? collectTextFieldRects(in: window)
            : []

        // Hand the rendered image off to a background queue for the heavy
        // scale / redaction-compositing / encode / base64 work so the main
        // thread is freed immediately (avoids hitch / watchdog under bursts).
        postProcessQueue.async { [weak self] in
            self?.postProcessAndEmit(
                image: image,
                originalSize: originalSize,
                redactRects: redactRects,
                trigger: trigger
            )
        }
    }

    /// Off-main post-processing. Operates only on the already-rendered
    /// `UIImage` and value types — no UIView/UIWindow access — so it is safe
    /// to run on a background queue. UIGraphicsImageRenderer drawing into a
    /// bitmap context is thread-safe.
    private func postProcessAndEmit(image: UIImage, originalSize: CGSize, redactRects: [CGRect], trigger: String) {
        guard let scaled = scaleImage(image) else { return }

        var finalImage = scaled
        if !redactRects.isEmpty {
            finalImage = redact(rects: redactRects, in: scaled, originalSize: originalSize)
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

    /// Composites opaque blocks over the given (unscaled-coordinate) rects on
    /// top of `image`. Pure image work — no UIView access — safe off-main.
    internal func redact(rects: [CGRect], in image: UIImage, originalSize: CGSize) -> UIImage {
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

        // UIKit text-bearing views. A secure UITextField (isSecureTextEntry)
        // is itself a UITextField, so it is already covered by this branch —
        // there is no separate `isSecureTextEntry` branch because it would be
        // unreachable. If this branch is ever narrowed, secure fields MUST be
        // re-added explicitly.
        if view is UITextField || view is UITextView {
            rects.append(view.convert(view.bounds, to: rootView))
        } else if Self.isSwiftUITextRendering(view) {
            // SwiftUI LIMITATION: SwiftUI's SecureField / TextField do NOT
            // render as UIKit UITextField/UITextView. They draw into private
            // host views (e.g. _UIGraphicsView / CGDrawingView / SwiftUI text
            // renderers) whose class names are not public API. The recursive
            // collector above therefore misses them entirely, which would
            // capture a SwiftUI SecureField's contents as PLAINTEXT PIXELS.
            // As a safety net, when redaction is enabled we also redact any
            // view whose class name matches SwiftUI's text-rendering host
            // views. This is heuristic (private class names can change across
            // OS versions); it errs on the side of over-redaction rather than
            // leaking a password. Callers that need stronger guarantees should
            // disable screenshot capture on screens with sensitive SwiftUI
            // input until a first-class SwiftUI redaction API exists.
            rects.append(view.convert(view.bounds, to: rootView))
        }

        for sub in view.subviews {
            collectTextFieldRectsRecursive(view: sub, rootView: rootView, rects: &rects)
        }
    }

    /// Heuristic detection of SwiftUI text-rendering host views by class name.
    /// SwiftUI does not expose its text host views as public types, so we
    /// match on the private class-name substrings SwiftUI uses to draw text
    /// (including SecureField/TextField content). See the comment in
    /// `collectTextFieldRectsRecursive` for the privacy rationale.
    private static func isSwiftUITextRendering(_ view: UIView) -> Bool {
        let name = String(describing: Swift.type(of: view))
        guard name.contains("SwiftUI") || name.hasPrefix("_UI") || name.hasPrefix("CG") else {
            return false
        }
        let lower = name.lowercased()
        return lower.contains("text") || lower.contains("secure") || lower.contains("graphicsview")
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
