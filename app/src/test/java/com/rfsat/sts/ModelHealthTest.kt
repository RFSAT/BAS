package com.rfsat.sts

import com.rfsat.bas.cloud.CloudSettings
import com.rfsat.bas.cloud.CloudSettings.ModelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored model choice, judged against the catalogue the service last
 * returned. This is the check that means a retired or text-only choice is
 * caught at a desk with a signal rather than as a 404 at the range — so its
 * verdicts are pinned here, where they cost nothing to test, instead of being
 * discovered in the field. The function is pure (no Context, no network),
 * which is what lets it be tested at all.
 */
class ModelHealthTest {

    private val listed = listOf(
        "deepseek-v4-flash-vision-exp" to "V4 Flash Vision — experimental, reads images",
        "deepseek-v4-flash" to "V4 Flash  [text only]",
        "deepseek-v4-pro" to "V4 Pro  [text only]"
    )

    @Test
    fun `nothing to check against is UNCHECKED, not a warning`() {
        // No catalogue fetched yet.
        assertEquals(ModelHealth.UNCHECKED, CloudSettings.modelHealth("deepseek-v4-pro", emptyList()))
        // No choice.
        assertEquals(ModelHealth.UNCHECKED, CloudSettings.modelHealth("", listed))
        // And UNCHECKED must never nag.
        assertEquals("", CloudSettings.modelHealthNote(ModelHealth.UNCHECKED, "x", "DeepSeek"))
    }

    @Test
    fun `a listed image-capable model is OK and silent`() {
        assertEquals(ModelHealth.OK,
            CloudSettings.modelHealth("deepseek-v4-flash-vision-exp", listed))
        assertEquals("", CloudSettings.modelHealthNote(ModelHealth.OK, "x", "DeepSeek"))
    }

    @Test
    fun `deepseek-v4-pro stays OK while the service keeps listing it`() {
        // DeepSeek reversed the 14 Sep 2026 reroute; v4-pro remains in the
        // catalogue. It is text-only, so it lands as TEXT_ONLY here — the
        // point is that it is NOT flagged as gone.
        assertEquals(ModelHealth.TEXT_ONLY, CloudSettings.modelHealth("deepseek-v4-pro", listed))
    }

    @Test
    fun `a choice the catalogue no longer carries is NOT_LISTED`() {
        val verdict = CloudSettings.modelHealth("deepseek-v4-pro-2024", listed)
        assertEquals(ModelHealth.NOT_LISTED, verdict)
        val note = CloudSettings.modelHealthNote(verdict, "deepseek-v4-pro-2024", "DeepSeek")
        assertTrue("the note must name the offending model", note.contains("deepseek-v4-pro-2024"))
        assertTrue("the note must name the service", note.contains("DeepSeek"))
    }

    @Test
    fun `a text-only choice is flagged as unable to score`() {
        val verdict = CloudSettings.modelHealth("deepseek-v4-flash", listed)
        assertEquals(ModelHealth.TEXT_ONLY, verdict)
        val note = CloudSettings.modelHealthNote(verdict, "deepseek-v4-flash", "DeepSeek")
        assertTrue("the note must name the offending model", note.contains("deepseek-v4-flash"))
        assertTrue("the note must say it cannot score", note.contains("cannot score a card"))
    }
}
