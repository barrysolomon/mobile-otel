package io.opentelemetry.android.demo

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import io.opentelemetry.android.demo.ui.debug.DebugToolbar
import io.opentelemetry.android.demo.ui.feed.FeedFragment
import io.opentelemetry.android.demo.ui.profile.ProfileFragment
import io.opentelemetry.android.demo.ui.shop.ShopFragment
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb

/**
 * Main activity for ShopSocial demo app.
 *
 * Features:
 * - Bottom navigation with 5 tabs
 * - Collapsible debug toolbar
 * - Fragment-based navigation
 * - Breadcrumb tracking
 */
class ShopSocialActivity : AppCompatActivity(), DebugToolbar.DebugToolbarListener {

    private lateinit var debugToolbar: DebugToolbar
    private lateinit var bottomNav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shop_social)

        debugToolbar = findViewById(R.id.debugToolbar)
        bottomNav = findViewById(R.id.bottom_navigation)

        debugToolbar.listener = this

        setupBottomNavigation()

        // Load initial fragment
        if (savedInstanceState == null) {
            loadFragment(FeedFragment())
        }

        // Track app launch breadcrumb
        trackBreadcrumb("app_launch", "ShopSocialActivity")
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_feed -> {
                    trackBreadcrumb("nav_feed", "FeedFragment")
                    FeedFragment()
                }
                R.id.nav_shop -> {
                    trackBreadcrumb("nav_shop", "ShopFragment")
                    ShopFragment()
                }
                R.id.nav_post -> {
                    trackBreadcrumb("nav_post", "PostFragment")
                    // TODO: Create PostFragment
                    return@setOnItemSelectedListener false
                }
                R.id.nav_likes -> {
                    trackBreadcrumb("nav_likes", "LikesFragment")
                    // TODO: Create LikesFragment
                    return@setOnItemSelectedListener false
                }
                R.id.nav_profile -> {
                    trackBreadcrumb("nav_profile", "ProfileFragment")
                    ProfileFragment()
                }
                else -> return@setOnItemSelectedListener false
            }

            loadFragment(fragment)
            true
        }

        // Select feed by default
        bottomNav.selectedItemId = R.id.nav_feed
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment, fragment)
            .commit()
    }

    private fun trackBreadcrumb(action: String, screen: String) {
        if (BreadcrumbManager.isInitialized()) {
            BreadcrumbManager.add(
                JourneyBreadcrumb.navigation(
                    screen = screen,
                    action = action
                )
            )
        }
    }

    // Debug Toolbar Listeners
    override fun onTriggerCrash() {
        trackBreadcrumb("trigger_crash", "DebugToolbar")
        Handler(Looper.getMainLooper()).postDelayed({
            throw RuntimeException("Debug crash triggered from toolbar")
        }, 500)
    }

    override fun onTriggerAnr() {
        trackBreadcrumb("trigger_anr", "DebugToolbar")
        // Block main thread for 6 seconds
        Thread.sleep(6000)
    }

    override fun onTriggerHttp500() {
        trackBreadcrumb("trigger_http500", "DebugToolbar")
        // TODO: Make API call that returns 500
    }

    override fun onTriggerMemoryPressure() {
        trackBreadcrumb("trigger_memory", "DebugToolbar")
        // Allocate large objects
        val largeList = mutableListOf<ByteArray>()
        repeat(100) {
            largeList.add(ByteArray(1024 * 1024)) // 1MB each
        }
    }

    override fun onTriggerJank() {
        trackBreadcrumb("trigger_jank", "DebugToolbar")
        // Force 10 dropped frames by blocking main thread
        Handler(Looper.getMainLooper()).post {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 200) {
                // Busy wait
            }
        }
    }

    override fun onClear() {
        trackBreadcrumb("clear_breadcrumbs", "DebugToolbar")
        if (BreadcrumbManager.isInitialized()) {
            BreadcrumbManager.clear()
        }
    }
}
