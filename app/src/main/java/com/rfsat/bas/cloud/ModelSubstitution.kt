package com.rfsat.bas.cloud

import com.rfsat.bas.log.Logger
import org.json.JSONObject

/**
 * Which model actually answered, against which one was asked for.
 *
 * A SERVICE CAN SUBSTITUTE A MODEL WITHOUT FAILING, and until now nothing in
 * this app noticed. From 12:00 Beijing on 14 September 2026 DeepSeek routes
 * every request for deepseek-v4-pro to V4.1 Flash and bills it at Flash's
 * price. The identifier does not stop working. It answers - as something else.
 *
 * That is the one failure mode every diagnostic added since 1.49.2 is blind
 * to. The refusal logging catches a service that says no: 400, 404, 429, 503,
 * an error payload wearing a 200. AnswerSanity catches a reply that was never
 * read - holes marching along an evenly stepped line. Neither notices a
 * well-formed, plausible answer from a model nobody asked for. It parses, it
 * plots, and it is wrong for a reason no log line mentions.
 *
 * RECORDED, NEVER REFUSED. A substitution is the service's decision to make,
 * and it may well be an improvement - DeepSeek's own claim is that V4.1 Flash
 * beats V4 Pro on every measure they publish. Refusing an answer because the
 * label changed would be this app overruling a vendor about its own service.
 * What matters is that the substitution stops being invisible, so a result
 * that makes no sense can be traced to the model that produced it rather than
 * guessed at.
 *
 * WHERE THE NAME COMES FROM. Anthropic and every OpenAI-shaped service name
 * the answering model in a top-level "model"; Gemini uses "modelVersion". A
 * service naming neither is passed over in silence rather than reported on:
 * absence of the field is not evidence of a substitution, and a log line
 * implying otherwise would be worse than none.
 *
 * This lives in its own file, and is internal rather than private, so that
 * SecondOpinion needs three one-line calls and nothing else. Editing one of
 * several near-identical transports is how 1.49.5 failed to compile, and the
 * smaller that edit is, the less room there is to repeat it.
 */
internal fun noteAnsweringModel(who: String, asked: String, reply: String) {
    val root = runCatching { JSONObject(reply) }.getOrNull() ?: return
    val answered = root.optString("model")
        .ifBlank { root.optString("modelVersion") }
        .takeIf { it.isNotBlank() && it != "null" } ?: return

    if (answered == asked) {
        Logger.i("SecondOpinion", "$who answered with $answered, as asked")
        return
    }

    // A VERSION SUFFIX IS NOT A SUBSTITUTION. Services routinely answer a
    // request for an alias with the dated build behind it - gemini-3.6-flash
    // replying as gemini-3.6-flash-002, or a "-latest" alias naming the
    // snapshot it resolved to. Reporting those as substitutions would fill
    // the Log with noise and teach whoever reads it to skip the one line that
    // matters.
    if (answered.startsWith(asked) || asked.startsWith(answered)) {
        Logger.i("SecondOpinion", "$who answered with $answered, which resolves $asked")
        return
    }

    Logger.w("SecondOpinion",
        "$who: $asked was asked for and $answered answered - the service substituted a model. " +
            "The reading that follows came from $answered, not from $asked.")
}