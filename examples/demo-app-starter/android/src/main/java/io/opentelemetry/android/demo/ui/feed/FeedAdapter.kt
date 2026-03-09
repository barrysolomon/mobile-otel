// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.feed

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import io.opentelemetry.android.demo.R

/**
 * Adapter for feed posts RecyclerView.
 */
class FeedAdapter : ListAdapter<FeedPost, FeedAdapter.FeedViewHolder>(FeedDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_feed_post, parent, false)
        return FeedViewHolder(view)
    }

    override fun onBindViewHolder(holder: FeedViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class FeedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val usernameText: TextView = itemView.findViewById(R.id.usernameText)
        private val contentText: TextView = itemView.findViewById(R.id.contentText)
        private val likesButton: MaterialButton = itemView.findViewById(R.id.likesButton)
        private val commentsButton: MaterialButton = itemView.findViewById(R.id.commentsButton)
        private val timeText: TextView = itemView.findViewById(R.id.timeText)

        fun bind(post: FeedPost) {
            usernameText.text = post.username
            contentText.text = post.content
            likesButton.text = "${post.likes} Likes"
            commentsButton.text = "${post.comments} Comments"
            timeText.text = formatTimestamp(post.timestamp)
        }

        private fun formatTimestamp(timestamp: Long): String {
            val diff = System.currentTimeMillis() - timestamp
            val hours = diff / (1000 * 60 * 60)
            return when {
                hours < 1 -> "Just now"
                hours < 24 -> "${hours}h ago"
                else -> "${hours / 24}d ago"
            }
        }
    }

    private class FeedDiffCallback : DiffUtil.ItemCallback<FeedPost>() {
        override fun areItemsTheSame(oldItem: FeedPost, newItem: FeedPost): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: FeedPost, newItem: FeedPost): Boolean {
            return oldItem == newItem
        }
    }
}
