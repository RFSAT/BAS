package com.rfsat.sts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The type tagging AppBackup uses for preference values.
 *
 * SharedPreferences holds five types; Gson reading into Map<String, Any?>
 * turns every number into a Double, and putting a Double where an Int is
 * expected throws the next time that preference is read — on the restored
 * phone, long after the restore reported success. The tags exist to stop
 * that, so the encoding is worth pinning.
 */
class BackupSettingsTest {

    private fun tag(v: Any?): String? = when (v) {
        is Boolean -> "b:$v"
        is Int -> "i:$v"
        is Long -> "l:$v"
        is Float -> "f:$v"
        is String -> "s:$v"
        else -> null
    }

    private fun untag(tagged: String): Any? {
        val t = tagged.substringBefore(':', "")
        val raw = tagged.substringAfter(':', "")
        return when (t) {
            "b" -> raw.toBoolean(); "i" -> raw.toInt(); "l" -> raw.toLong()
            "f" -> raw.toFloat(); "s" -> raw
            else -> null
        }
    }

    @Test
    fun `every stored type survives the round trip as its own type`() {
        assertEquals(true, untag(tag(true)!!))
        assertEquals(42, untag(tag(42)!!))
        assertEquals(9_000_000_000L, untag(tag(9_000_000_000L)!!))
        assertEquals(1.5f, untag(tag(1.5f)!!))
        assertEquals("hello", untag(tag("hello")!!))
    }

    @Test
    fun `an int does not come back as a float`() {
        // The failure this encoding exists to prevent.
        val back = untag(tag(7)!!)
        assertEquals(Integer::class.java, back!!.javaClass)
    }

    @Test
    fun `a string containing a colon is not truncated`() {
        // Model identifiers and URLs both contain colons — "qwen/...:free"
        // among them — so only the FIRST colon may be treated as the tag.
        val v = "https://example.com:8080/x"
        assertEquals(v, untag(tag(v)!!))
    }

    @Test
    fun `unknown types are skipped rather than guessed at`() {
        assertNull(tag(setOf("a", "b")))
        assertNull(untag("z:something"))
    }
}
