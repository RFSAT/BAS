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
    val readsImages: Boolean = true
) {
    ANTHROPIC("Claude (Anthropic)", "sk-ant-…", "console.anthropic.com"),
    OPENAI("OpenAI", "sk-…", "platform.openai.com"),

    /**
     * DeepSeek speaks the OpenAI chat-completions dialect, so it costs almost
     * nothing to support and shares the same request builder.
     *
     * readsImages = false because DeepSeek's own published API documents the
     * chat models as TEXT-ONLY: user content is a string, not a content-part
     * list with an image_url. Third-party write-ups claim otherwise, and they
     * are describing proxies and hosted platforms rather than
     * api.deepseek.com.
     *
     * It is still wired up in full. If a vision-capable model appears on the
     * account, typing its identifier under "Other" is all that is needed —
     * the transport is already correct, and the flag above only decides
     * whether the app warns first.
     */
    DEEPSEEK("DeepSeek", "sk-…", "platform.deepseek.com", readsImages = false);

    /** What the Settings pickers show. Every task here is a question about a
     *  photograph, so "cannot read a photograph" is the single most useful
     *  thing to know before choosing one. */
    val pickerLabel: String get() = if (readsImages) label else "$label — text only"
}
