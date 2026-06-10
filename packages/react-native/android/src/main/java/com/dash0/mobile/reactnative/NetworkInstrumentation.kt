/*
 * NetworkInstrumentation — the single shared seam between the two halves of
 * the Android network story that run at different times:
 *
 *   1. [Dash0MobilePackage.createNativeModules] (pre-JS): creates the
 *      [OTelNetworkInterceptor] and registers it on React Native's shared
 *      OkHttp client via OkHttpClientProvider. This MUST happen before any JS
 *      runs so expo/fetch's OkHttp client picks it up.
 *
 *   2. [OTelMobileCallSink.start] (when JS calls `Dash0Mobile.start`): the
 *      tracer only exists after OTelMobile has been started, so this is where
 *      the interceptor is armed with the tracer + collector-host ignore list.
 *
 * A process-wide singleton bridges the two: the package installs it, the sink
 * arms it. Splitting "install" from "arm" is what keeps the interceptor a safe
 * pass-through no-op for the window between process launch and `start()`.
 */
package com.dash0.mobile.reactnative

object NetworkInstrumentation {
    /**
     * The one interceptor instance registered on the OkHttp pipeline. Created
     * lazily and held for the process lifetime — the OkHttp client factory
     * captures this exact reference, and [OTelMobileCallSink] arms/disarms the
     * same reference on start/shutdown.
     */
    val interceptor: OTelNetworkInterceptor by lazy { OTelNetworkInterceptor() }
}
