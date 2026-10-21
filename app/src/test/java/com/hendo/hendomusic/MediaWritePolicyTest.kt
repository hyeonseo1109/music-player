package com.hendo.hendomusic

import com.hendo.hendomusic.library.requiresMediaWriteApproval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaWritePolicyTest {
    @Test
    fun `app owned trimmed media writes without an approval request`() {
        assertFalse(requiresMediaWriteApproval(36, 41551L, "com.hendo.hendomusic", "com.hendo.hendomusic"))
    }

    @Test
    fun `another app media still requires approval`() {
        assertTrue(requiresMediaWriteApproval(36, 10L, "com.android.providers.media", "com.hendo.hendomusic"))
    }

    @Test
    fun `unknown owner remains on safe approval path`() {
        assertTrue(requiresMediaWriteApproval(36, 10L, null, "com.hendo.hendomusic"))
    }

    @Test
    fun `legacy Android and SAF items do not use Android 11 approval request`() {
        assertFalse(requiresMediaWriteApproval(29, 10L, "other", "com.hendo.hendomusic"))
        assertFalse(requiresMediaWriteApproval(36, null, "other", "com.hendo.hendomusic"))
    }
}
