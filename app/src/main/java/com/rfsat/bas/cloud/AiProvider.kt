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
enum class AiProvider(
    val label: String,
    val keyHint: String,
    val console: String,
    val readsImages: Boolean = true,
    val selectable: Boolean = true
) {
    ANTHROPIC("Claude (Anthropic)", "sk-ant-…", "console.anthropic.com"),
    OPENAI("OpenAI", "sk-…", "platform.openai.com"),

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
