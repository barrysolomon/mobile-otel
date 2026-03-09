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

/**
 * Main activity for ShopSocial demo app.
 *
 * Features:
 * - Bottom navigation with 5 tabs
 * - Collapsible debug toolbar
 * - Fragment-based navigation
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
    }

    private fun setupBottomNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            val fragment = when (item.itemId) {
                R.id.nav_feed -> FeedFragment()
                R.id.nav_shop -> ShopFragment()
                R.id.nav_post -> return@setOnItemSelectedListener false  // TODO: Create PostFragment
                R.id.nav_likes -> return@setOnItemSelectedListener false  // TODO: Create LikesFragment
                R.id.nav_profile -> ProfileFragment()
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

    // Debug Toolbar Listeners
    override fun onTriggerCrash() {
        Handler(Looper.getMainLooper()).postDelayed({
            throw RuntimeException("Debug crash triggered from toolbar")
        }, 500)
    }

    override fun onTriggerAnr() {
        // Block main thread for 6 seconds
        Thread.sleep(6000)
    }

    override fun onTriggerHttp500() {
        // TODO: Make API call that returns 500
    }

    override fun onTriggerMemoryPressure() {
        // Allocate large objects
        val largeList = mutableListOf<ByteArray>()
        repeat(100) {
            largeList.add(ByteArray(1024 * 1024)) // 1MB each
        }
    }

    override fun onTriggerJank() {
        // Force dropped frames by blocking main thread
        Handler(Looper.getMainLooper()).post {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 200) {
                // Busy wait
            }
        }
    }

    override fun onClear() {
        // no-op in starter
    }

    override fun onOpenRingBuffer() {
        // no-op in starter
    }
}
