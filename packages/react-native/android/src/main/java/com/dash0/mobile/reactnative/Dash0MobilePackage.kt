/*
 * Copyright 2025 Barry Solomon
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dash0.mobile.reactnative

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.network.OkHttpClientFactory
import com.facebook.react.modules.network.OkHttpClientProvider
import com.facebook.react.uimanager.ViewManager
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient

class Dash0MobilePackage : ReactPackage {

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        // Install the OkHttp interceptor BEFORE any JS runs. createNativeModules
        // is invoked during ReactInstanceManager bring-up, ahead of the JS
        // bundle executing — which is exactly the requirement: expo/fetch (Expo
        // SDK 52+) builds its OkHttp client off OkHttpClientProvider the first
        // time JS issues a request, so the factory must already be replaced by
        // then.
        //
        // The interceptor itself is a pass-through no-op until the sink arms it
        // on `Dash0Mobile.start`, so installing it this early is safe even if
        // the app never calls start().
        installOkHttpInterceptor()
        return listOf(Dash0MobileModule(reactContext))
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        emptyList()

    private fun installOkHttpInterceptor() {
        // Idempotent: createNativeModules can run more than once (JS reload /
        // multiple ReactInstanceManagers). Install exactly once per process; the
        // interceptor instance is a process-wide singleton, so one install
        // covers every later ReactInstanceManager.
        if (!installed.compareAndSet(false, true)) return
        try {
            // Set a factory that builds RN's DEFAULT-configured client and adds
            // our interceptor on top.
            //
            // We use createClientBuilder() rather than capturing+delegating to a
            // "previous" factory because react-native exposes no stable
            // getOkHttpClientFactory() — it is absent in react-android 0.76 (the
            // declared peer), so capturing the previous factory does not compile
            // against the supported RN version. createClientBuilder() returns
            // RN's default builder (timeouts, cookie jar, default interceptors),
            // so we preserve RN's standard client config and only add ours. This
            // is the documented RN interceptor-injection pattern. A host that had
            // installed its OWN custom factory is replaced with default+interceptor
            // (the unavoidable trade without a getter).
            OkHttpClientProvider.setOkHttpClientFactory(
                InterceptorInstallingFactory(NetworkInstrumentation.interceptor),
            )
        } catch (_: Throwable) {
            // Installing instrumentation must NEVER crash host bring-up. If the
            // RN networking module isn't present or the API shape changed, we
            // ship without native network capture rather than taking the app
            // down. Telemetry is always subordinate to the host. Reset the guard
            // so a later attempt can retry.
            installed.set(false)
        }
    }

    private companion object {
        private val installed = AtomicBoolean(false)
    }
}

/**
 * Builds RN's default-configured OkHttp client (via [OkHttpClientProvider.createClientBuilder])
 * and adds the Dash0 network interceptor as an *application* interceptor — so it
 * sees the logical request (redirects collapsed into one call) and injects
 * `traceparent` once per logical request.
 */
private class InterceptorInstallingFactory(
    private val interceptor: OTelNetworkInterceptor,
) : OkHttpClientFactory {
    override fun createNewNetworkModuleClient(): OkHttpClient =
        OkHttpClientProvider.createClientBuilder()
            .addInterceptor(interceptor)
            .build()
}
