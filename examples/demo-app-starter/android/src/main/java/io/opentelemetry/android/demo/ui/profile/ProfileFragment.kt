// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.opentelemetry.android.demo.R

/**
 * Profile fragment showing user info and settings.
 *
 * Demonstrates:
 * - User profile display
 * - Settings options
 * - Logout functionality
 */
class ProfileFragment : Fragment() {

    private lateinit var usernameText: TextView
    private lateinit var emailText: TextView
    private lateinit var logoutButton: Button
    private lateinit var editProfileButton: Button
    private lateinit var notificationsButton: Button
    private lateinit var privacyButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        usernameText = view.findViewById(R.id.usernameText)
        emailText = view.findViewById(R.id.emailText)
        logoutButton = view.findViewById(R.id.logoutButton)
        editProfileButton = view.findViewById(R.id.editProfileButton)
        notificationsButton = view.findViewById(R.id.notificationsButton)
        privacyButton = view.findViewById(R.id.privacyButton)

        loadUserProfile()
        setupButtons()
    }

    private fun loadUserProfile() {
        // TODO: Load from user session when implemented
        usernameText.text = "demo_user"
        emailText.text = "demo@example.com"
    }

    private fun setupButtons() {
        editProfileButton.setOnClickListener {
            Toast.makeText(context, "Edit profile coming soon", Toast.LENGTH_SHORT).show()
        }

        notificationsButton.setOnClickListener {
            Toast.makeText(context, "Notifications settings coming soon", Toast.LENGTH_SHORT).show()
        }

        privacyButton.setOnClickListener {
            Toast.makeText(context, "Privacy settings coming soon", Toast.LENGTH_SHORT).show()
        }

        logoutButton.setOnClickListener {
            performLogout()
        }
    }

    private fun performLogout() {
        // TODO: Implement session clearing and navigation to login
        Toast.makeText(context, "Logout functionality coming soon", Toast.LENGTH_SHORT).show()
    }
}
