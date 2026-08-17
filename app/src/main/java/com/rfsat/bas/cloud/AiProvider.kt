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
    val freeAccess: FreeAccess = FreeAccess.NONE
) {
    ANTHROPIC("Claude (Anthropic)", "sk-ant-…", "console.anthropic.com"),
    OPENAI("OpenAI", "sk-…", "platform.openai.com",
        tokenLimitField = "max_completion_tokens"),

    /**
     * One key, most of the field. OpenRouter proxies the OpenAI
     * chat-completions API to several hundred models from every major
     * vendor, so a shooter who wants a second opinion from a model this app
     * does not integrate directly can simply name it.
     *
     * The catch is that OpenRouter's catalogue is a superset of what this app
     * can use: not every model behind it reads images, and not every one
     * honours a strict json_schema. Choosing a text-only model there produces
     * the same failure DeepSeek would have — which is why the models listed
     * for it are ones that do both, and why "Other" carries a warning.
     */
    OPENROUTER("OpenRouter", "sk-or-v1-…", "openrouter.ai/keys",
        freeAccess = FreeAccess.SELECTABLE),

    /** Grok. OpenAI-compatible down to the path, with vision models and
     *  structured outputs. */
    XAI("xAI (Grok)", "xai-…", "console.x.ai"),

    /** Pixtral and its successors. Also OpenAI-shaped, and Mistral supports
     *  json_schema with strict mode, which is what this app needs. */
    MISTRAL("Mistral", "…", "console.mistral.ai", freeAccess = FreeAccess.ACCOUNT),

    /**
     * The one provider here that is not OpenAI-shaped: Gemini has its own
     * request, its own schema dialect and its own way of carrying the key, so
     * it has a transport of its own in SecondOpinion rather than an endpoint
     * constant.
     *
     * Worth that extra code for one reason above the others: the free tier is
     * usable. A shooter who will not put a card on file for an API can still
     * get a second opinion, which is the difference between a feature that
     * exists and a feature that gets used.
     */
    GEMINI("Google Gemini", "AIza…", "aistudio.google.com",
        freeAccess = FreeAccess.ACCOUNT),

    /**
     * NOT OFFERED. Kept because the transport is correct and the day DeepSeek
     * ships vision this is a one-word change, but hidden from the pickers
     * because it cannot do this app's job at all.
     *
     * This is not an inference from third-party articles — those disagree
     * with each other. It is what api-docs.deepseek.com publishes for
     * POST /chat/completions:
     *
     *   * a user message's `content` is a STRING. There is no content-part
     *     list, so there is no way to attach an image. Every question this
     *     app asks is about a photograph.
     *   * `response_format.type` is one of `text` or `json_object`. There is
     *     no `json_schema`, so the schema-constrained answering this app
     *     depends on is unavailable on that route. (Tool calls do support
     *     strict mode, which would be the way in — if images were possible.)
     *   * the token limit is `max_tokens`, not `max_completion_tokens`.
     *
     * The models are `deepseek-v4-flash` and `deepseek-v4-pro`; the
     * `deepseek-chat` / `deepseek-reasoner` identifiers shipped in 1.36.0
     * were already out of date, which is its own argument for not offering
     * a service nobody here can test against.
     */
    DEEPSEEK(
        "DeepSeek", "sk-…", "platform.deepseek.com",
        readsImages = false, selectable = false
    );

    /** What the Settings pickers show. Every task here is a question about a
     *  photograph, so "cannot read a photograph" is the single most useful
     *  thing to know before choosing one. */
    val pickerLabel: String get() = if (readsImages) label else "$label — text only"

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
