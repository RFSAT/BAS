package com.rfsat.sts

import com.rfsat.bas.detect.DetectionAudit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionAuditTest {

    @Test
    fun `an empty record says so instead of showing zeroes`() {
        val s = DetectionAudit.Summary(0, 0, 0, 0, 0.0)
        assertTrue(s.describe().contains("No comparisons recorded yet"))
    }

    @Test
    fun `the agreement rate counts everything either side saw`() {
        // 8 agreed, 1 the app invented, 1 it missed: 8 of 10, not 8 of 9.
        // Counting only the app's own marks would flatter it by hiding misses.
        val s = DetectionAudit.Summary(3, 8, 1, 1, 1.4)
        assertEquals(10, s.total)
        assertTrue(s.describe().contains("80%"))
    }

    @Test
    fun `the summary refuses to be read as ground truth`() {
        val s = DetectionAudit.Summary(5, 40, 3, 2, 1.1)
        val text = s.describe()
        assertTrue("it must say neither side is ground truth",
            text.contains("Neither side is ground truth"))
        assertTrue("it must report both disagreement directions",
            text.contains("App marked but service did not see")
                && text.contains("Service saw but app did not mark"))
    }

    @Test
    fun `the pair distance is only reported when there is something to pair`() {
        assertTrue(!DetectionAudit.Summary(2, 0, 4, 4, 0.0).describe()
            .contains("Mean distance"))
        assertTrue(DetectionAudit.Summary(2, 6, 0, 0, 2.3).describe()
            .contains("Mean distance"))
    }
}
