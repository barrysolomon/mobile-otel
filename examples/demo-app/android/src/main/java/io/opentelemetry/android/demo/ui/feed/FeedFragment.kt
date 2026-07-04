// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.feed

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb

/**
 * Feed fragment showing social posts.
 *
 * Demonstrates:
 * - RecyclerView with scroll performance (jank detection)
 * - Pull to refresh
 * - Network requests (mock feed API)
 * - User interactions (like/comment)
 */
class FeedFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var adapter: FeedAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_feed, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.feedRecyclerView)
        swipeRefresh = view.findViewById(R.id.swipeRefresh)

        setupRecyclerView()
        setupSwipeRefresh()

        // Load initial data
        loadFeed()

        // Track breadcrumb
        trackBreadcrumb("view_feed")
    }

    private fun setupRecyclerView() {
        adapter = FeedAdapter()
        recyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@FeedFragment.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            trackBreadcrumb("pull_to_refresh")
            loadFeed()
        }
    }

    private fun loadFeed() {
        // TODO: Make network request to mock API
        // For now, just show mock data
        val mockPosts = generateMockPosts()
        adapter.submitList(mockPosts)
        swipeRefresh.isRefreshing = false

        trackBreadcrumb("feed_loaded")
    }

    private fun generateMockPosts(): List<FeedPost> {
        return listOf(
            FeedPost(
                id = "1",
                username = "john_doe",
                content = "Check out this awesome product! 🎉",
                imageUrl = null,
                likes = 42,
                comments = 8,
                timestamp = System.currentTimeMillis() - 3600000
            ),
            FeedPost(
                id = "2",
                username = "jane_smith",
                content = "Just got my new shoes from the shop! Love them ❤️",
                imageUrl = null,
                likes = 128,
                comments = 23,
                timestamp = System.currentTimeMillis() - 7200000
            ),
            FeedPost(
                id = "3",
                username = "tech_enthusiast",
                content = "This OpenTelemetry demo is amazing 📊",
                imageUrl = null,
                likes = 89,
                comments = 15,
                timestamp = System.currentTimeMillis() - 10800000
            )
        )
    }

    private fun trackBreadcrumb(action: String) {
        if (BreadcrumbManager.isInitialized()) {
            BreadcrumbManager.add(
                JourneyBreadcrumb.custom(
                    screen = "FeedFragment",
                    action = action
                )
            )
        }
    }
}

/**
 * Data class for feed posts.
 */
data class FeedPost(
    val id: String,
    val username: String,
    val content: String,
    val imageUrl: String?,
    val likes: Int,
    val comments: Int,
    val timestamp: Long
)
