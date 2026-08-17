package com.rfsat.sts

import com.rfsat.bas.cloud.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider table is data, and the failures it can cause are all remote:
 * a wrong field name is a 400 from someone else's server, seen by a shooter
 * and not by CI. So the invariants are pinned here.
 */
class AiProviderTest {

    @Test
    fun `every offered provider can read an image`() {
        // Every task in this app is a question about a photograph, so a
        // text-only provider must never reach a picker.
        for (p in AiProvider.OFFERED) {
            assertTrue("${p.label} is offered but cannot read images", p.readsImages)
        }
    }

    @Test
    fun `DeepSeek is present but withheld`() {
        assertFalse(AiProvider.DEEPSEEK.selectable)
        assertFalse(AiProvider.OFFERED.contains(AiProvider.DEEPSEEK))
        assertTrue("the entry itself must survive for the day it works",
            AiProvider.entries.contains(AiProvider.DEEPSEEK))
    }

    @Test
    fun `a withdrawn choice falls back to one that is offered`() {
        assertTrue(AiProvider.offeredOr(AiProvider.DEEPSEEK).selectable)
        assertEquals(AiProvider.OPENAI, AiProvider.offeredOr(AiProvider.OPENAI))
    }

    @Test
    fun `only OpenAI uses the renamed token limit field`() {
        assertEquals("max_completion_tokens", AiProvider.OPENAI.tokenLimitField)
        for (p in AiProvider.entries.filter { it != AiProvider.OPENAI }) {
            assertEquals("${p.label} should use max_tokens", "max_tokens", p.tokenLimitField)
        }
    }

    @Test
    fun `every provider names a console a shooter can get a key from`() {
        for (p in AiProvider.entries) {
            assertTrue("${p.label} has no console", p.console.contains("."))
            assertTrue("${p.label} has no label", p.label.isNotBlank())
        }
    }

    @Test
    fun `every offered provider has a model list and a default that is in it`() {
        // A default outside its own list shows an empty spinner and silently
        // sends a model the shooter never chose.
        for (p in AiProvider.OFFERED) {
            val list = com.rfsat.bas.cloud.CloudSettings.MODELS[p].orEmpty()
            val default = com.rfsat.bas.cloud.CloudSettings.DEFAULT_MODEL[p]
            assertTrue("${p.label} offers no models", list.isNotEmpty())
            assertTrue("${p.label} has no default model", !default.isNullOrBlank())
            assertTrue("${p.label}'s default '$default' is not in its own list",
                list.any { it.first == default })
        }
    }

    @Test
    fun `provider names are distinct enough to tell apart in a picker`() {
        val labels = AiProvider.OFFERED.map { it.pickerLabel }
        assertEquals("two providers share a label", labels.size, labels.toSet().size)
    }

    @Test
    fun `only OpenRouter offers free access the app can actually choose`() {
        // The checkbox is only a control where free access is a different
        // MODEL. Where it belongs to the key — Gemini, Mistral — the app can
        // do nothing about it, and a control that pretends otherwise would
        // suggest requests are free when they are being billed.
        val selectable = AiProvider.entries.filter {
            it.freeAccess == com.rfsat.bas.cloud.FreeAccess.SELECTABLE
        }
        assertEquals(listOf(AiProvider.OPENROUTER), selectable)
    }

    @Test
    fun `services with an account-level free tier say so rather than offering a switch`() {
        for (p in listOf(AiProvider.GEMINI, AiProvider.MISTRAL)) {
            assertEquals(com.rfsat.bas.cloud.FreeAccess.ACCOUNT, p.freeAccess)
            assertTrue("${p.label} must explain its free tier",
                p.freeAccessNote.contains("free tier"))
        }
    }

    @Test
    fun `paid-only services say that plainly`() {
        for (p in listOf(AiProvider.ANTHROPIC, AiProvider.OPENAI, AiProvider.XAI)) {
            assertEquals(com.rfsat.bas.cloud.FreeAccess.NONE, p.freeAccess)
            assertTrue("${p.label} must say it is paid", p.freeAccessNote.contains("paid only"))
        }
    }

    @Test
    fun `the free model list only exists where free access is selectable`() {
        for ((p, list) in com.rfsat.bas.cloud.CloudSettings.FREE_MODELS) {
            assertEquals("${p.label} lists free models but cannot select them",
                com.rfsat.bas.cloud.FreeAccess.SELECTABLE, p.freeAccess)
            assertTrue("${p.label}'s free list is empty", list.isNotEmpty())
        }
    }
}
