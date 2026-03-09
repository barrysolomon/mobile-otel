// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

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
import io.opentelemetry.android.demo.ui.appointments.AppointmentsFragment
import io.opentelemetry.android.demo.ui.debug.DebugToolbar
import io.opentelemetry.android.demo.ui.directions.DirectionsFragment
import io.opentelemetry.android.demo.ui.scheduling.SchedulingProfileFragment

class SchedulingActivity : AppCompatActivity(), DebugToolbar.DebugToolbarListener {

    private lateinit var debugToolbar: DebugToolbar
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scheduling)

        debugToolbar = findViewById(R.id.debugToolbar)
        bottomNav = findViewById(R.id.bottom_navigation)
        debugToolbar.listener = this
        debugToolbar.visibility = View.GONE

        setupBottomNavigation()

        if (savedInstanceState == null) {
            loadFragment(CalendarFragment())
        }
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_calendar     -> CalendarFragment()
                R.id.nav_appointments -> AppointmentsFragment()
                R.id.nav_book         -> BookFragment()
                R.id.nav_directions   -> DirectionsFragment()
                R.id.nav_profile      -> SchedulingProfileFragment()
                else -> return@setOnItemSelectedListener false
            }
            loadFragment(fragment)
            true
        }
        bottomNav.selectedItemId = R.id.nav_calendar
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .commit()
    }

    // DebugToolbar callbacks — fault scenarios for demo purposes

    override fun onTriggerCrash() {
        Handler(Looper.getMainLooper()).postDelayed({
            throw RuntimeException("Simulated booking service crash")
        }, 500)
    }

    override fun onTriggerAnr() {
        // Block main thread to trigger ANR detection
        Thread.sleep(6000)
    }

    override fun onTriggerHttp500() {
        // Force next Appointments pull-to-refresh to fail
        AppointmentRepository.forceNextFetchError = true
        // Navigate to Appointments so user sees the error
        bottomNav.selectedItemId = R.id.nav_appointments
    }

    override fun onTriggerMemoryPressure() {
        val sink = mutableListOf<ByteArray>()
        repeat(100) { sink.add(ByteArray(1024 * 1024)) }
    }

    override fun onTriggerJank() {
        Handler(Looper.getMainLooper()).post {
            val deadline = System.currentTimeMillis() + 200
            while (System.currentTimeMillis() < deadline) { /* busy wait */ }
        }
    }

    override fun onClear() {
        // no-op in starter
    }

    override fun onOpenRingBuffer() {
        // no-op in starter
    }
}
