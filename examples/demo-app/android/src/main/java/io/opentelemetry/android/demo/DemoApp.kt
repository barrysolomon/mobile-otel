package io.opentelemetry.android.demo

import android.app.Application
import android.util.Log
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.android.mobile.autocapture.AutoCaptureOptions

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()

        val config = ConfigManager.loadConfig(this)
        OTelMobile.start(
            application = this,
            config = config,
            options = AutoCaptureOptions()
        )

        Log.i("OTELDemoApp", "OTelMobile started (auto-capture enabled)")
    }
}
