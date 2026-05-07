// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.ui.post

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import io.opentelemetry.android.demo.R
import io.opentelemetry.android.mobile.breadcrumb.BreadcrumbManager
import io.opentelemetry.android.mobile.breadcrumb.JourneyBreadcrumb

class PostFragment : Fragment() {

    private lateinit var captionInput: EditText
    private lateinit var postButton: MaterialButton
    private lateinit var addPhotoButton: MaterialButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_post, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        captionInput = view.findViewById(R.id.captionInput)
        postButton = view.findViewById(R.id.postButton)
        addPhotoButton = view.findViewById(R.id.addPhotoButton)

        setupButtons()
        trackBreadcrumb("view_create_post")
    }

    private fun setupButtons() {
        addPhotoButton.setOnClickListener {
            trackBreadcrumb("tap_add_photo")
            Toast.makeText(context, "Camera integration coming soon", Toast.LENGTH_SHORT).show()
        }

        postButton.setOnClickListener {
            val caption = captionInput.text.toString().trim()
            if (caption.isEmpty()) {
                trackBreadcrumb("post_validation_error", mapOf("reason" to "empty_caption"))
                Toast.makeText(context, "Please add a caption", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            trackBreadcrumb("tap_post", mapOf("caption_length" to caption.length.toString()))
            Toast.makeText(context, "Post shared!", Toast.LENGTH_SHORT).show()
            captionInput.text.clear()
        }
    }

    private fun trackBreadcrumb(action: String, attributes: Map<String, String> = emptyMap()) {
        if (BreadcrumbManager.isInitialized()) {
            BreadcrumbManager.add(
                JourneyBreadcrumb.custom(
                    screen = "PostFragment",
                    action = action,
                    attributes = attributes
                )
            )
        }
    }
}
