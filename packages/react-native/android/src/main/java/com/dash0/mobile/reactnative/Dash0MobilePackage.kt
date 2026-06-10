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
        // Idempotent: createNativeModules can be called more than once (e.g. on
        // a JS reload / multiple ReactInstanceManagers). Replacing the factory
        // repeatedly would stack DelegatingOkHttpClientFactory wrappers, so
        // install exactly once per process. The interceptor instance is a
        // process-wide singleton, so a single install covers every later
        // ReactInstanceManager.
        if (!installed.compareAndSet(false, true)) return
        try {
            // Capture whatever factory is currently in effect so we DELEGATE to
            // it instead of replacing the host's OkHttp configuration. RN's
            // default factory builds the standard client; a host that set its
            // own factory keeps all of its customizations — we only add one
            // interceptor on top.
            val previous: OkHttpClientFactory = OkHttpClientProvider.getOkHttpClientFactory()
            OkHttpClientProvider.setOkHttpClientFactory(
                DelegatingOkHttpClientFactory(previous, NetworkInstrumentation.interceptor),
            )
        } catch (_: Throwable) {
            // Installing instrumentation must NEVER crash host bring-up. If the
            // RN networking module isn't present or the API shape changed, we
            // simply ship without native network capture rather than taking the
            // app down. Telemetry is always subordinate to the host. Reset the
            // guard so a later attempt can retry.
            installed.set(false)
        }
    }

    private companion object {
        private val installed = AtomicBoolean(false)
    }
}

/**
 * Wraps the host's existing [OkHttpClientFactory], adding the Dash0 network
 * interceptor as an *application* interceptor (so it sees the logical request,
 * including redirects collapsed into one call, and injects `traceparent` once
 * per logical request).
 */
private class DelegatingOkHttpClientFactory(
    private val delegate: OkHttpClientFactory,
    private val interceptor: OTelNetworkInterceptor,
) : OkHttpClientFactory {
    override fun createNewNetworkModuleClient(): OkHttpClient {
        val base = delegate.createNewNetworkModuleClient()
        return base.newBuilder()
            .addInterceptor(interceptor)
            .build()
    }
}
