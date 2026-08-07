package io.github.bakahuiii.selene.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SyncthingRuntimeStatusTest {
    @Test
    fun diagnosticRemovesPrivatePathsAndNormalizesWhitespace() {
        val diagnostic = SyncthingRuntimeStatus.sanitizeForStorage(
            "IOException:\n/data/user/0/selene/files/core\tfailed",
            listOf("/data/user/0/selene/files"),
        )

        assertEquals("IOException: <private>/core failed", diagnostic)
        assertFalse(diagnostic.contains("/data/user"))
    }

    @Test
    fun diagnosticKeepsOnlyBoundedTail() {
        val diagnostic = SyncthingRuntimeStatus.sanitizeForStorage("x".repeat(900), emptyList())

        assertEquals(800, diagnostic.length)
    }
}
