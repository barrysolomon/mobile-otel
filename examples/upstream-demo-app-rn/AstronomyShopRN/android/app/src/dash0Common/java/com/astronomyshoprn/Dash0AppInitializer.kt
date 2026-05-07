package com.astronomyshoprn

import android.app.Activity

object Dash0AppInitializer {
    @JvmStatic
    fun init(activity: Activity) {
        val cellId = activity.intent?.getStringExtra("DASH0_CELL_ID")
        ExportConfig.load(activity)
        SdkInitializer.initialize(activity.application, cellId)
    }
}
