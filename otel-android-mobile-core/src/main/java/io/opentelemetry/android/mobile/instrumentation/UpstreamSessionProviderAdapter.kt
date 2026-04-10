// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.mobile.instrumentation

import io.opentelemetry.android.session.SessionProvider

internal class UpstreamSessionProviderAdapter(
    private val upstream: SessionProvider
) : MobileSessionProvider {
    override fun getSessionId(): String = upstream.getSessionId()
    override fun getViewId(): String = ""
    override fun getCurrentScreenName(): String? = null
    override fun getPreviousScreenName(): String? = null
    override fun getTimeOnScreenMs(): Long = 0L
    override fun onScreenView(screenName: String) {}
    override fun onAppForeground(timestampMs: Long): Boolean = false
    override fun onAppBackground(timestampMs: Long) {}
}
