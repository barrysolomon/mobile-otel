package io.opentelemetry.android.demo

import android.app.Application
import android.util.Log
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.android.mobile.autocapture.AutoCaptureOptions

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Force CONTINUOUS mode so exports happen on schedule without a policy gateway.
        // Overwrites any cached CONDITIONAL value from SharedPreferences.
        getSharedPreferences("otel_config", android.content.Context.MODE_PRIVATE)
            .edit().putString("export_mode", "CONTINUOUS").apply()

        val config = ConfigManager.loadConfig(this)
        val capture = ConfigManager.loadCaptureOptions(this)
        val captureOptions = AutoCaptureOptions(
            captureLifecycle  = capture[ConfigManager.captureKey("lifecycle")]   ?: true,
            captureScreens    = capture[ConfigManager.captureKey("screens")]     ?: true,
            captureTaps       = capture[ConfigManager.captureKey("taps")]        ?: true,
            captureLongPress  = capture[ConfigManager.captureKey("long_press")]  ?: true,
            captureSwipe      = capture[ConfigManager.captureKey("swipe")]       ?: true,
            captureScroll     = capture[ConfigManager.captureKey("scroll")]      ?: true,
            captureTextInput  = capture[ConfigManager.captureKey("text_input")]  ?: true,
            captureBackPress  = capture[ConfigManager.captureKey("back_press")]  ?: true,
            captureFragments  = capture[ConfigManager.captureKey("fragments")]   ?: true
        )
        OTelMobile.start(
            application = this,
            config = config,
            options = captureOptions
        )

        Log.i("OTELDemoApp", "OTelMobile started (auto-capture enabled)")
    }
}
