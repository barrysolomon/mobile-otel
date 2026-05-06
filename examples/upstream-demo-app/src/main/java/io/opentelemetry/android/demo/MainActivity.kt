/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.opentelemetry.android.demo.about.AboutActivity
import io.opentelemetry.android.demo.theme.DemoAppTheme
import io.opentelemetry.android.demo.shop.ui.AstronomyShopActivity
import io.opentelemetry.android.demo.shop.ui.products.multiThreadCrashing

class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<DemoViewModel>()
    private var gate2Fired: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (OtelDemoApplication.openTelemetry == null) {
            val cellId = intent?.getStringExtra("DASH0_CELL_ID")
            SdkInitializer.initialize(application, cellId)
        }
        setContent {
            DemoAppTheme {
                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Row(
                            Modifier.padding(all = 20.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CenterText(
                                fontSize = 40.sp,
                                text =
                                    buildAnnotatedString {
                                        withStyle(style = SpanStyle(color = Color(0xFFF5A800))) {
                                            append("Open")
                                        }
                                        withStyle(style = SpanStyle(color = Color(0xFF425CC7))) {
                                            append("Telemetry")
                                        }
                                        withStyle(style = SpanStyle(color = Color.Black)) {
                                            append(" Android Demo")
                                        }
                                        toAnnotatedString()
                                    },
                            )
                        }
                        SessionId(viewModel.sessionIdState)
                        MainOtelButton(
                            painterResource(id = R.drawable.otel_icon),
                        )
                        val context = LocalContext.current
                        LauncherButton(text = "Go shopping", onClick = {
                            context.startActivity(Intent(this@MainActivity, AstronomyShopActivity::class.java))
                        })
                        LauncherButton(text = "Learn more", onClick = {
                            context.startActivity(Intent(this@MainActivity, AboutActivity::class.java))
                        })

                    }
                }
                Log.d(TAG, "Main Activity started ")
            }
        }
        viewModel.sessionIdState.value = OtelDemoApplication.sessionId.ifEmpty { "? unknown ?" }

        // Request the correct phone state permission based on API level
        // This permission is needed for gathering certain network information like
        // carrier name and network subtype (LTE, 4G) on certain API levels.
        val phoneStatePermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_BASIC_PHONE_STATE
        } else {
            Manifest.permission.READ_PHONE_STATE
        }

        if (ContextCompat.checkSelfPermission(this, phoneStatePermission)
            != PackageManager.PERMISSION_GRANTED) {
            // Permission is not granted, request it
            ActivityCompat.requestPermissions(
                this,
                arrayOf(phoneStatePermission),
                100
            )
        }
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        // CRITICAL: setIntent() updates getIntent() so onResume sees the
        // new extras (e.g., gate3_crash). Without this, am start
        // delivers the new intent but getIntent() keeps returning the
        // original — verified 2026-05-05 in UAT cell 2 investigation.
        setIntent(newIntent)
    }

    override fun onResume() {
        super.onResume()
        // Gate 2 (matchy-matchy): one-shot HTTP call so the four-gate
        // runbook can observe a CLIENT span with `http.request.method=GET`
        // and `server.address=httpbin.org`. Mirrors the iOS native +
        // RN iOS demos which also fire httpbin.org/get from launch UI.
        if (!gate2Fired) {
            gate2Fired = true
            Gate2Probe.fire(this)
        }
        // Gate 3 (matchy-matchy): deterministic crash trigger via launch
        // intent extra. Mirrors iOS native's -DASH0_CRASH_NOW launch arg.
        // Use: adb shell am start -n .../MainActivity --ez gate3_crash true
        if (intent?.getBooleanExtra("gate3_crash", false) == true) {
            // Clear the extra so we don't re-crash on re-launch
            intent.removeExtra("gate3_crash")
            Log.i(TAG, "Gate3: scheduling crash in 3s (after telemetry buffer warms)")
            android.os.Handler(mainLooper).postDelayed({
                Log.w(TAG, "Gate3: crashing now")
                multiThreadCrashing()
            }, 3000)
        }
    }

}
