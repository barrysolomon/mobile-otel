package com.astronomyshoprn

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "AstronomyShopRN"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)

    // Test hook: if launched with --ez gate3_crash true, schedule a fatal
    // crash ~3s after boot. Mirrors the native Android demo's hook (and
    // iOS's -DASH0_CRASH_NOW) so the matchy-matchy Gate 3 runbook can drive
    // a real uncaught exception on RN Android without a human tap. The SDK's
    // ErrorsInstrumentation captures the throw, mirrors the RAM buffer to
    // disk, and the next launch's recovery path emits app.crash.
    // Use: adb shell am start -n com.astronomyshoprn/.MainActivity --ez gate3_crash true
    if (intent?.getBooleanExtra("gate3_crash", false) == true) {
      intent.removeExtra("gate3_crash")  // don't re-crash on re-launch
      android.util.Log.i("AstronomyShopRN", "Gate3: scheduling crash in 3s")
      android.os.Handler(mainLooper).postDelayed({
        android.util.Log.w("AstronomyShopRN", "Gate3: crashing now")
        throw RuntimeException("Dash0 RN Android Gate 3 test crash")
      }, 3000L)
    }
  }
}
