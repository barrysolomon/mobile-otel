// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Help screen providing documentation and usage instructions.
 */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Help"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
