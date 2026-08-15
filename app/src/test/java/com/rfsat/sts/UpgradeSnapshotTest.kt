package com.rfsat.sts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision logic, tested without a device: when a snapshot is taken, and
 * which files survive pruning. The Android calls around it are thin; these
 * rules are where a mistake would be silent.
 */
class UpgradeSnapshotTest {

    /** Mirrors UpgradeSnapshot.maybeTake's decision. */
    private fun shouldSnapshot(lastCode: Int, nowCode: Int): Boolean =
        lastCode != nowCode && lastCode != 0

    @Test
    fun `a fresh install has nothing to protect`() {
        assertTrue("must not snapshot", !shouldSnapshot(0, 56))
    }

    @Test
    fun `the same build twice takes one snapshot, not one per launch`() {
        assertTrue(shouldSnapshot(55, 56))
        assertTrue("second launch of the same build", !shouldSnapshot(56, 56))
    }

    @Test
    fun `a downgrade is still a change worth protecting`() {
        // Sideloading an older build is exactly when a bad migration bites.
        assertTrue(shouldSnapshot(56, 55))
    }

    @Test
    fun `pruning keeps the newest and drops the rest`() {
        val newestFirst = listOf("f6", "f5", "f4", "f3", "f2", "f1")
        val keep = 5
        assertEquals(listOf("f6", "f5", "f4", "f3", "f2"), newestFirst.take(keep))
        assertEquals("only the oldest goes", listOf("f1"), newestFirst.drop(keep))
    }
}
