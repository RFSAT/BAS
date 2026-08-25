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

    /**
     * WAS "DeepSeek is present but withheld", and it failed the moment
     * DeepSeek was offered — which is the test doing its job. It pinned a
     * decision, the decision changed for a stated reason (vision arrived on
     * 21 August 2026), and the pin moved with it deliberately rather than
     * being deleted.
     */
    @Test
    fun `DeepSeek is offered now that it reads images`() {
        assertTrue(AiProvider.DEEPSEEK.selectable)
        assertTrue(AiProvider.DEEPSEEK.readsImages)
        assertTrue(AiProvider.OFFERED.contains(AiProvider.DEEPSEEK))
        assertTrue("a service offered with reservations must carry them where it is chosen",
            AiProvider.DEEPSEEK.caution.isNotBlank())
    }

    /**
     * Every entry is offered as of 1.52.0, so this no longer has a withdrawn
     * provider to test with. The invariant is what matters and it is stated
     * generally: whatever is stored in settings, the picker must end up on
     * something a shooter can actually choose — otherwise the spinner shows
     * nothing selected and the next tap silently changes the setting.
     */
    @Test
    fun `a stored choice always resolves to one that is offered`() {
        for (p in AiProvider.entries) {
            assertTrue("${p.label} resolves to something unselectable",
                AiProvider.offeredOr(p).selectable)
        }
        assertEquals("an offered provider must resolve to itself",
            AiProvider.OPENAI, AiProvider.offeredOr(AiProvider.OPENAI))
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

    @Test
    fun `routed models say who routes them`() {
        // OpenRouter resells other vendors' models, so a label like
        // "Claude Sonnet 5" is indistinguishable from the entry under the
        // standalone Anthropic service — same model, different key, different
        // bill. Every routed entry has to name the router.
        val cs = com.rfsat.bas.cloud.CloudSettings
        val routed = cs.MODELS[AiProvider.OPENROUTER].orEmpty() +
            cs.FREE_MODELS[AiProvider.OPENROUTER].orEmpty()
        assertTrue("OpenRouter offers no models", routed.isNotEmpty())
        for ((id, label) in routed) {
            assertTrue("'$label' does not say it is routed", label.contains("OpenRouter"))
            assertTrue("'$id' is not a vendor-prefixed OpenRouter identifier", id.contains("/"))
        }
    }

    @Test
    fun `only OpenRouter uses vendor-prefixed identifiers`() {
        // A slash in a direct service's model id means an OpenRouter name was
        // pasted into the wrong list, which fails as a 404 that looks like the
        // model was withdrawn.
        val cs = com.rfsat.bas.cloud.CloudSettings
        for (p in AiProvider.OFFERED.filter { it != AiProvider.OPENROUTER }) {
            for ((id, _) in cs.MODELS[p].orEmpty()) {
                assertTrue("${p.label} model '$id' looks like an OpenRouter identifier",
                    !id.contains("/"))
            }
        }
    }

    @Test
    fun `OpenRouter is offered last`() {
        // The pickers are built straight from OFFERED, so this order is the
        // on-screen order. OpenRouter belongs at the bottom: it is a router,
        // and every model it sells belongs to a vendor listed above it.
        assertEquals(AiProvider.OPENROUTER, AiProvider.OFFERED.last())
    }

    /**
     * THE ORDER IS EVIDENCE, NOT TASTE, so it is pinned like any other
     * measurement. On one 15-shot card: Claude and Gemini 3.6/3.5 Flash
     * placed every hole correctly; Mistral invented positions on all three
     * of its vision models; OpenRouter's two free entries had been
     * withdrawn by its own catalogue. xAI and OpenAI are untested here and
     * sit between the two groups.
     *
     * If this fails, either the order changed or the evidence did — and the
     * second is the only good reason.
     */
    @Test
    fun `services are ordered by what scored the test card`() {
        assertEquals(
            listOf(
                AiProvider.ANTHROPIC, AiProvider.GEMINI, AiProvider.XAI,
                AiProvider.OPENAI, AiProvider.MISTRAL, AiProvider.DEEPSEEK,
                AiProvider.OPENROUTER
            ),
            AiProvider.OFFERED
        )
    }

    @Test
    fun `a service that failed on the card is labelled rather than removed`() {
        assertTrue("Mistral must still be reachable — it may work on another card",
            AiProvider.OFFERED.contains(AiProvider.MISTRAL))
        assertTrue("and it must say what it did here",
            AiProvider.MISTRAL.caution.startsWith("INACCURATE"))
        assertTrue("the picker itself must warn, not just the entry",
            AiProvider.MISTRAL.pickerLabel.contains("inaccurate"))
    }

    /**
     * THE ministral-3-14b-latest GUARD. That identifier was written into this
     * project from a docs page listing "Ministral 3 14B" as a display NAME,
     * with the alias guessed from it; Mistral answered invalid_model. Nothing
     * here can prove an identifier exists remotely, but it can refuse the two
     * shapes that produced every stale entry so far: an identifier that reads
     * like prose, and one already known to have been withdrawn.
     */
    @Test
    fun `no model identifier looks like it was guessed from a display name`() {
        val cs = com.rfsat.bas.cloud.CloudSettings
        val withdrawn = setOf(
            "ministral-3-14b-latest",
            "pixtral-large-latest",
            "grok-2-vision-latest",
            "gemini-3.1-pro",
            "gemini-2.5-flash",
            "qwen/qwen2.5-vl-72b-instruct:free",
            "meta-llama/llama-3.2-11b-vision-instruct:free"
        )
        val every = AiProvider.entries.flatMap {
            cs.MODELS[it].orEmpty() + cs.FREE_MODELS[it].orEmpty()
        }
        for ((id, _) in every) {
            assertFalse("$id was withdrawn by its service and must not come back", id in withdrawn)
            assertFalse("$id contains a space — identifiers do not", id.contains(" "))
            assertFalse("$id is capitalised like a display name, not an identifier",
                id.any { c -> c.isUpperCase() })
        }
    }
}
