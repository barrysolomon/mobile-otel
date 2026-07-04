// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.likes

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb

class LikesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_likes, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.likesRecyclerView)
        emptyText = view.findViewById(R.id.emptyLikesText)

        setupRecyclerView()
        loadLikedItems()
        trackBreadcrumb("view_likes")
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(context)
    }

    private fun loadLikedItems() {
        // No liked items yet — show empty state
        recyclerView.visibility = View.GONE
        emptyText.visibility = View.VISIBLE
        trackBreadcrumb("likes_empty")
    }

    private fun trackBreadcrumb(action: String, attributes: Map<String, String> = emptyMap()) {
        if (BreadcrumbManager.isInitialized()) {
            BreadcrumbManager.add(
                JourneyBreadcrumb.custom(
                    screen = "LikesFragment",
                    action = action,
                    attributes = attributes
                )
            )
        }
    }
}
