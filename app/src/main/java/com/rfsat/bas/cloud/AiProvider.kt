package com.rfsat.bas.cloud

/**
 * Which service is asked to look at the card.
 *
 * Interchangeable from the app's side: each is sent the same rectified
 * picture and the same question, and each answers against the same schema.
 * Nothing downstream knows or cares which replied.
 *
 * [readsImages] is the one thing that is NOT interchangeable, and it is
 * recorded here rather than discovered at the API. Every task this app asks a
 * model to do involves a photograph of a target — there is no text-only job
 * to fall back to — so a provider whose API takes text alone cannot do the
 * work at all. Saying so in the picker is kinder than a 400 from the service
 * with a message about message content types.
 */
/**
 * How, if at all, a service can be used without paying.
 *
 * The distinction matters because only one of these is something the APP can
 * act on. A checkbox that claims to switch on free access, and in fact does
 * nothing because the free tier is a property of the account, is worse than
 * no checkbox: it invites the belief that money is not being spent.
 */
enum class FreeAccess {
    /** Paid only. Nothing to offer. */
    NONE,

    /** A free tier exists but belongs to the KEY, not the request — Gemini's
     *  AI Studio keys and Mistral's free plan both work this way. The app
     *  cannot switch it on or off, and says so instead of pretending. */
    ACCOUNT,

    /** Free access is a different set of MODELS, which the app can choose —
     *  OpenRouter's ":free" variants. This is the only case where a checkbox
     *  changes what gets sent. */
    SELECTABLE
}

/**
 * DECLARATION ORDER IS PICKER ORDER. Both service spinners are built from
 * [AiProvider.OFFERED], which preserves the order below, so moving an entry
 * here moves it on screen — and adding one in the middle inserts it there.
 *
 * OpenRouter sits last on purpose: it is a router rather than a service of
 * its own, and every model it offers belongs to a vendor listed above it.
 * Reading the list top to bottom therefore goes direct services first, then
 * the way to reach anything else.
 */
enum class AiProvider(
    val label: String,
    val keyHint: String,
    val console: String,
    val readsImages: Boolean = true,
    val selectable: Boolean = true,
    /**
     * Which name this service gives the reply-length limit.
     *
     * OpenAI renamed max_tokens to max_completion_tokens and the others did
     * not follow, so "OpenAI-compatible" is compatible in shape and not in
     * every field name. Sending the wrong one is a 400 that talks about an
     * unknown parameter, which reads like a broken app rather than a wrong
     * spelling — and it is precisely the kind of detail that made DeepSeek
     * look supported when it was not.
     */
    val tokenLimitField: String = "max_tokens",
    val freeAccess: FreeAccess = FreeAccess.NONE,
    /**
     * What testing on a real card found, in the shooter's own words, or "".
     *
     * Not a rating and not the maker's description: this carries only what
     * has been OBSERVED here. A service is never removed for failing — it is
     * labelled, kept, and left to the shooter, because a service that fails
     * on this card may work on another and removing it hides that.
     */
    val caution: String = ""
) {
    // ORDER IS PICKER ORDER, and it is ordered by what testing on a real
    // card found rather than by capability on paper. On one 15-shot card:
    // Claude and Gemini 3.5/3.6 Flash put every hole in the right place;
    // Mistral invented positions on all three of its models; OpenRouter's
    // two free entries no longer exist.
    ANTHROPIC("Claude (Anthropic)", "sk-ant-…", "console.anthropic.com",
        caution = "Found all 15 holes correctly on the test card."),

    /**
     * The one provider here that is not OpenAI-shaped: Gemini has its own
     * request, its own schema dialect and its own way of carrying the key, so
     * it has a transport of its own in SecondOpinion rather than an endpoint
     * constant.
     *
     * Worth that extra code for two reasons. The free tier is usable, so a
     * shooter who will not put a card on file can still get a second
     * opinion; and 3.5 and 3.6 Flash both scored the test card correctly,
     * which puts it level with Claude on the only evidence that matters here.
     */
    GEMINI("Google Gemini", "AIza…", "aistudio.google.com",
        freeAccess = FreeAccess.ACCOUNT,
        caution = "3.5 and 3.6 Flash found all 15 holes on the test card. " +
            "3.7 Flash is frequently busy; Flash-Lite was close but not accurate."),

    /** Grok. OpenAI-compatible down to the path, with vision models and
     *  structured outputs. Not yet tested here on a card. */
    XAI("xAI (Grok)", "xai-…", "console.x.ai",
        caution = "Not yet tested on a card."),

    OPENAI("OpenAI", "sk-…", "platform.openai.com",
        tokenLimitField = "max_completion_tokens",
        caution = "Not yet tested on a card."),

    /**
     * OpenAI-shaped, supports json_schema with strict mode, and answers every
     * request — with invented positions.
     *
     * All three of its current vision models were tried on one card whose
     * fifteen shots were in and around the black. Medium 3.5 returned holes
     * marching from the centre to the top-right corner; Large 3 put them
     * around the centre but nowhere near the shots; Small 4 drew a diagonal
     * cross over the black. AnswerSanity catches the first and third; the
     * second is simply wrong rather than patterned, and nothing but the
     * shooter's eye will catch that.
     *
     * Kept and labelled. A service that fails on this card may work on
     * another, and removing it would hide that from anyone who wants to try.
     */
    MISTRAL("Mistral", "…", "console.mistral.ai", freeAccess = FreeAccess.ACCOUNT,
        caution = "INACCURATE on the test card: all three vision models invented " +
            "shot positions. Not recommended until it can be shown to work."),

    /**
     * Vision arrived on 21 August 2026 with deepseek-v4-flash-vision-exp,
     * which is why this is no longer hidden. It takes images through the same
     * chat-completions endpoint the text models use.
     *
     * OFFERED WITH A WARNING, not a recommendation. DeepSeek caps an image at
     * 384 tokens and normalises to roughly 800x800, which is a coarse look at
     * a target card — a 5 mm hole on a 170 mm face is about four pixels
     * across at that size. It may well not resolve holes at all. It is also
     * an experimental model with no published weights or technical report.
     * There is one further thing a shooter should know before choosing it,
     * and it is not technical: the request and the photograph are processed
     * on servers in China, under that jurisdiction.
     *
     * Still no json_schema on this route — response_format is text or
     * json_object only — so the JSON-mode fallback added in 1.49.4 is what
     * makes it answerable at all.
     */
    DEEPSEEK(
        "DeepSeek", "sk-…", "platform.deepseek.com",
        caution = "Experimental vision, and images are reduced to about 800x800 — " +
            "likely too coarse for shot holes. Processed in China."
    ),

    /**
     * One key, most of the field. OpenRouter proxies the OpenAI
     * chat-completions API to several hundred models from every major
     * vendor, so a shooter who wants a second opinion from a model this app
     * does not integrate directly can simply name it.
     *
     * PLACED LAST BECAUSE ITS FREE TIER IS NOT DEPENDABLE. Both free models
     * this app named were gone from OpenRouter's own catalogue by August
     * 2026 — one answered 404 "unavailable for free", the other "not found
     * on this account". The free lineup rotates constantly, so no free
     * identifier written here can stay right; they are discovered from the
     * catalogue instead. A PAID OpenRouter key reaches the whole field and
     * works normally.
     */
    OPENROUTER("OpenRouter", "sk-or-v1-…", "openrouter.ai/keys",
        freeAccess = FreeAccess.SELECTABLE,
        caution = "The free lineup rotates and both models this app used to name are gone. " +
            "Press Refresh to discover what is free today; a paid key works normally.");

    /** What the Settings pickers show. Every task here is a question about a
     *  photograph, so "cannot read a photograph" is the single most useful
     *  thing to know before choosing one. */
    val pickerLabel: String get() = when {
        !readsImages -> "$label — text only"
        caution.startsWith("INACCURATE") -> "$label — inaccurate here"
        this == OPENROUTER -> "$label — free tier unreliable"
        this == DEEPSEEK -> "$label — experimental, coarse images"
        else -> label
    }

    /** What to say under the free-models checkbox for this service. */
    val freeAccessNote: String get() = when (freeAccess) {
        FreeAccess.SELECTABLE ->
            "OpenRouter publishes free variants of some models, marked \u201c:free\u201d. They are " +
            "rate-limited, they come and go, and many do not support the schema-constrained " +
            "answering this app needs — so a free model may fail where a paid one succeeds."
        FreeAccess.ACCOUNT ->
            "$label has a free tier, but it belongs to the key rather than to the request: it " +
            "applies automatically within its limits and there is nothing here to switch on."
        FreeAccess.NONE ->
            "$label is paid only. Every request spends credit on the key."
    }

    companion object {
        /**
         * The providers a shooter may choose. An entry can exist in this enum
         * without being offered: a service that cannot do the job is worse
         * than a missing one, because picking it costs a round trip, an error
         * message and the suspicion that the feature is broken.
         */
        val OFFERED: List<AiProvider> get() = entries.filter { it.selectable }

        /** Falls back when a stored choice names a provider no longer
         *  offered — otherwise the spinner would show nothing selected and
         *  the next tap would silently change the setting. */
        fun offeredOr(p: AiProvider): AiProvider =
            if (p.selectable) p else OFFERED.firstOrNull() ?: ANTHROPIC
    }
}
