// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.opentelemetry.android.demo.data.AppointmentRepository
import io.opentelemetry.android.demo.ui.book.BookFragment
import io.opentelemetry.android.demo.ui.calendar.CalendarFragment
import io.opentelemetry.android.demo.ui.debug.RingBufferActivity
import io.opentelemetry.android.demo.ui.appointments.AppointmentsFragment
import io.opentelemetry.android.demo.ui.debug.DebugToolbar
import io.opentelemetry.android.demo.ui.directions.DirectionsFragment
import io.opentelemetry.android.demo.ui.scheduling.SchedulingProfileFragment
import io.opentelemetry.android.mobile.OTelMobile
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb
import io.opentelemetry.api.common.AttributeKey

class SchedulingActivity : AppCompatActivity(), DebugToolbar.DebugToolbarListener {

    private lateinit var debugToolbar: DebugToolbar
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scheduling)

        debugToolbar = findViewById(R.id.debugToolbar)
        bottomNav = findViewById(R.id.bottom_navigation)
        debugToolbar.listener = this
        debugToolbar.visibility = if (TelemetryFlags.showDebugToolbar) View.VISIBLE else View.GONE

        setupBottomNavigation()

        if (savedInstanceState == null) {
            loadFragment(CalendarFragment())
        }

        crumb("app_launch", "SchedulingActivity")
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            val (destination, fragment) = when (item.itemId) {
                R.id.nav_calendar     -> "CalendarFragment"     to CalendarFragment()
                R.id.nav_appointments -> "AppointmentsFragment" to AppointmentsFragment()
                R.id.nav_book         -> "BookFragment"         to BookFragment()
                R.id.nav_directions   -> "DirectionsFragment"   to DirectionsFragment()
                R.id.nav_profile      -> "SchedulingProfileFragment" to SchedulingProfileFragment()
                else -> return@setOnItemSelectedListener false
            }
            crumb("nav_${destination.lowercase()}", destination)

            // Trace navigation → render when captureInteractionTraces is enabled
            if (TelemetryFlags.captureInteractionTraces) {
                val span = OTelMobile.getTracer("schedulr.interactions")
                    .spanBuilder("user.navigate")
                    .setAttribute(AttributeKey.stringKey("screen.destination"), destination)
                    .startSpan()
                loadFragment(fragment)
                span.addEvent("ui.render.complete")
                span.end()
            } else {
                loadFragment(fragment)
            }
            true
        }
        bottomNav.selectedItemId = R.id.nav_calendar
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .commit()
    }

    private fun crumb(action: String, screen: String) {
        if (BreadcrumbManager.isInitialized()) {
            BreadcrumbManager.add(JourneyBreadcrumb.navigation(screen = screen, action = action))
        }
    }

    // DebugToolbar callbacks — fault scenarios for demo purposes

    override fun onTriggerCrash() {
        crumb("trigger_crash", "DebugToolbar")
        Handler(Looper.getMainLooper()).postDelayed({
            throw RuntimeException("Simulated booking service crash")
        }, 500)
    }

    override fun onTriggerAnr() {
        crumb("trigger_anr", "DebugToolbar")
        // Block main thread to trigger ANR detection
        Thread.sleep(6000)
    }

    override fun onTriggerHttp500() {
        crumb("trigger_http500", "DebugToolbar")
        // Force next Appointments pull-to-refresh to fail
        AppointmentRepository.forceNextFetchError = true
        // Navigate to Appointments so user sees the error
        bottomNav.selectedItemId = R.id.nav_appointments
    }

    override fun onTriggerMemoryPressure() {
        crumb("trigger_memory", "DebugToolbar")
        val sink = mutableListOf<ByteArray>()
        repeat(100) { sink.add(ByteArray(1024 * 1024)) }
    }

    override fun onTriggerJank() {
        crumb("trigger_jank", "DebugToolbar")
        Handler(Looper.getMainLooper()).post {
            val deadline = System.currentTimeMillis() + 200
            while (System.currentTimeMillis() < deadline) { /* busy wait */ }
        }
    }

    override fun onClear() {
        crumb("clear_breadcrumbs", "DebugToolbar")
        if (BreadcrumbManager.isInitialized()) BreadcrumbManager.clear()
    }

    override fun onOpenRingBuffer() {
        startActivity(Intent(this, RingBufferActivity::class.java))
    }
}
