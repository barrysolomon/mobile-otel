package com.astronomyshoprn

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "AstronomyShopRN"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: android.os.Bundle?) {
    // Dash0 flavors: init SDK before RN bridge. Dash0AppInitializer only
    // exists in dash0Common — upstream flavor hits ClassNotFoundException.
    try {
      Class.forName("com.astronomyshoprn.Dash0AppInitializer")
          .getMethod("init", android.app.Activity::class.java)
          .invoke(null, this)
    } catch (_: ClassNotFoundException) {
      // upstream flavor — SDK init deferred to JS-side Dash0Mobile.start()
    }

    super.onCreate(savedInstanceState)

    maybeScheduleGate3Crash(intent)
  }

  // The UAT runner triggers the crash via
  //   `am start --activity-single-top ... --ez gate3_crash true`
  // which delivers the intent to `onNewIntent` when MainActivity is already
  // running (true in CONTINUOUS mode, where the SDK's periodic exporter keeps
  // references alive between lifecycle cycles). Without this override the
  // gate3 flag is silently dropped and the test crash never fires — manifests
  // as `rn-android cell 2 (cont/online/yes)` failing recovery_present=0.
  override fun onNewIntent(intent: android.content.Intent?) {
    super.onNewIntent(intent)
    if (intent != null) {
      setIntent(intent)
      maybeScheduleGate3Crash(intent)
    }
  }

  private fun maybeScheduleGate3Crash(intent: android.content.Intent?) {
    if (intent?.getBooleanExtra("gate3_crash", false) == true) {
      intent.removeExtra("gate3_crash")
      android.util.Log.i("AstronomyShopRN", "Gate3: scheduling crash in 3s")
      android.os.Handler(mainLooper).postDelayed({
        throw RuntimeException("Dash0 RN Android Gate 3 test crash")
      }, 3000L)
    }
  }
}
