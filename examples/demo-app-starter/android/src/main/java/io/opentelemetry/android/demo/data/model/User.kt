// Copyright 2025 The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo.data.model

/**
 * User data model.
 */
data class User(
    val id: String,
    val username: String,
    val email: String,
    val displayName: String = username,
    val avatarUrl: String? = null,
    val bio: String? = null,
    val followersCount: Int = 0,
    val followingCount: Int = 0
)
