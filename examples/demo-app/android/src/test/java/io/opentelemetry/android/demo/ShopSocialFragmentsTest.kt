// Copyright 2025 Barry Solomon
// SPDX-License-Identifier: Apache-2.0

package io.opentelemetry.android.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.opentelemetry.android.demo.ui.post.PostFragment
import io.opentelemetry.android.demo.ui.likes.LikesFragment
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = TestDemoApplication::class)
class ShopSocialFragmentsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ========== PostFragment ==========

    @Test
    fun `PostFragment can be instantiated`() {
        val fragment = PostFragment()
        assertNotNull(fragment)
    }

    @Test
    fun `PostFragment has correct class name for breadcrumb tracking`() {
        assertEquals("PostFragment", PostFragment::class.java.simpleName)
    }

    // ========== LikesFragment ==========

    @Test
    fun `LikesFragment can be instantiated`() {
        val fragment = LikesFragment()
        assertNotNull(fragment)
    }

    @Test
    fun `LikesFragment has correct class name for breadcrumb tracking`() {
        assertEquals("LikesFragment", LikesFragment::class.java.simpleName)
    }
}
