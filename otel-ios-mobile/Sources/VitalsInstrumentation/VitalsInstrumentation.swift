/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
import Foundation
import OpenTelemetryApi
import OTelMobileCore
#if canImport(UIKit)
import UIKit
#endif

/// Records app-performance vitals as OTel events:
/// - `app.start` with `start_duration_ms` (time from install() to first frame)
/// - `ui.jank` on CADisplayLink frame-time threshold crossings
/// - `app.memory_warning` on UIApplication memory pressure
///
/// For continuous gauges (memory used / battery / thermal / storage) use the
/// separate `DeviceStatsCollector` on the SDK — that runs on an explicit
/// timer. This module emits discrete events only.
public final class VitalsInstrumentation: @unchecked Sendable {
    public static let shared = VitalsInstrumentation()

    private let lock = NSLock()
    private var installed = false
    private var logger: Logger?
    private var appStartTime: Date = Date()
    private var jankThresholdMs: Double = 100
    private var lastFrameAt: CFTimeInterval = 0

    #if canImport(UIKit) && os(iOS)
    private var displayLink: CADisplayLink?
    #endif

    private init() {}

    public func install(
        logger: Logger,
        jankThresholdMs: Double = 100,
        captureAppStart: Bool = true,
        captureJank: Bool = true,
        captureMemoryWarnings: Bool = true
    ) {
        lock.lock(); defer { lock.unlock() }
        guard !installed else { return }
        installed = true
        self.logger = logger
        self.jankThresholdMs = jankThresholdMs
        self.appStartTime = Date()

        if captureAppStart {
            DispatchQueue.main.async { [weak self] in
                self?.emitAppStart()
            }
        }

        #if canImport(UIKit) && os(iOS)
        if captureJank {
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                let link = CADisplayLink(target: self, selector: #selector(self.onFrame(_:)))
                link.add(to: .main, forMode: .common)
                self.displayLink = link
            }
        }
        if captureMemoryWarnings {
            NotificationCenter.default.addObserver(
                self,
                selector: #selector(onMemoryWarning),
                name: UIApplication.didReceiveMemoryWarningNotification,
                object: nil
            )
        }
        #endif
    }

    public func uninstall() {
        lock.lock(); defer { lock.unlock() }
        installed = false
        #if canImport(UIKit) && os(iOS)
        displayLink?.invalidate()
        displayLink = nil
        NotificationCenter.default.removeObserver(self)
        #endif
    }

    // MARK: - Events

    private func emitAppStart() {
        let elapsedMs = Int(Date().timeIntervalSince(appStartTime) * 1000)
        lock.lock()
        let logger = self.logger
        lock.unlock()
        logger?.logRecordBuilder()
            .setBody(AttributeValue.string("app.start"))
            .setSeverity(.info)
            .setAttributes([
                "event.name": .string("app.start"),
                "start_duration_ms": .int(elapsedMs),
            ])
            .emit()
    }

    #if canImport(UIKit) && os(iOS)
    @objc private func onFrame(_ link: CADisplayLink) {
        let now = link.targetTimestamp
        let previous = lastFrameAt
        lastFrameAt = now
        guard previous > 0 else { return }

        let deltaMs = (now - previous) * 1000.0
        if deltaMs >= jankThresholdMs {
            lock.lock()
            let logger = self.logger
            let threshold = self.jankThresholdMs
            lock.unlock()
            logger?.logRecordBuilder()
                .setBody(AttributeValue.string("ui.jank"))
                .setSeverity(.warn)
                .setAttributes([
                    "event.name": .string("ui.jank"),
                    "frame_time_ms": .double(deltaMs),
                    "threshold_ms": .double(threshold),
                ])
                .emit()
        }
    }

    @objc private func onMemoryWarning() {
        lock.lock()
        let logger = self.logger
        lock.unlock()
        logger?.logRecordBuilder()
            .setBody(AttributeValue.string("app.memory_warning"))
            .setSeverity(.warn)
            .setAttributes(["event.name": .string("app.memory_warning")])
            .emit()
    }
    #endif
}
