package com.rfsat.bas.cloud

import android.content.Context
import com.rfsat.bas.log.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Asks the service what models THIS key can actually reach.
 *
 * WHY A HAND-WRITTEN LIST CANNOT BE KEPT RIGHT. Five identifiers shipped in
 * the picker stopped existing within a few months of being written:
 * pixtral-large-latest was retired, grok-2-vision-latest was retired, and two
 * of the three Gemini entries were withdrawn. Each failed only when a shooter
 * pressed the button, at a range, with a card in front of them. No amount of
 * care in maintaining a constant fixes that, because the list ages between
 * releases and the app cannot know it has.
 *
 * Every one of these services publishes the answer. The curated list stays as
 * a starting point for a shooter with no key yet, and this replaces it the
 * moment there is a key to ask with.
 *
 * WHAT EACH CATALOGUE ACTUALLY ANSWERS, because they are not the same
 * question. Anthropic, OpenAI, xAI, Mistral and DeepSeek return what THIS KEY
 * can reach. OpenRouter's catalogue is PUBLIC and global — it lists the whole
 * field regardless of key — so "not on this key" cannot be inferred there, and
 * what it does give instead is per-model pricing, which is the only reliable
 * way to know what is free today.
 *
 * NOTHING IS HIDDEN. An earlier draft cut the list to the models that both
 * read images and are reachable on the current key, which is the wrong
 * instinct twice over: a shooter cannot choose a model they cannot see, and a
 * model missing from the list looks like an app that does not support it
 * rather than an account that does not have it. Every model the service
 * publishes is offered, and the ones that would need something changed are
 * MARKED - "text only" where the service says it cannot read a picture, and
 * "not on this key" for an entry that is known to exist but did not come back
 * for this account. Where a service does not say which of its models read
 * images, nothing is marked, because guessing from a name would be a lie
 * dressed as help.
 */
object ModelCatalog {

    private const val TIMEOUT_MS = 20_000

    /** Enough for any account; a picker is not a mirror of a catalogue. */
    private const val MAX_PAGES = 5

    /** Where each service publishes its catalogue. */
    private fun endpoint(p: AiProvider): String? = when (p) {
        AiProvider.ANTHROPIC -> "https://api.anthropic.com/v1/models?limit=100"
        AiProvider.OPENAI -> "https://api.openai.com/v1/models"
        AiProvider.XAI -> "https://api.x.ai/v1/models"
        AiProvider.MISTRAL -> "https://api.mistral.ai/v1/models"
        AiProvider.OPENROUTER -> "https://openrouter.ai/api/v1/models"
        AiProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200"
        AiProvider.DEEPSEEK -> "https://api.deepseek.com/models"
    }

    sealed class Result {
        data class Ok(
            /** Everything the service published, in the order it should be shown. */
            val models: List<Pair<String, String>>,
            /** How many of them the service says can be asked about a picture,
             *  or null where it does not say. */
            val readImages: Int?
        ) : Result()
        data class Failed(val message: String) : Result()
    }

    /** Blocking; call it off the main thread. Never logs the key. */
    fun fetch(provider: AiProvider, apiKey: String): Result {
        val base = endpoint(provider) ?: return Result.Failed("No catalogue to ask.")
        if (apiKey.isBlank()) return Result.Failed(
            "${provider.label} needs its key before it can say which models this account has.")

        return runCatching {
            val bodies = ArrayList<String>()
            var url = base
            // TWO OF THE SIX PAGINATE and would otherwise be read short in
            // silence — a truncated list looks like a small account rather
            // than half an answer. Anthropic pages on has_more/last_id,
            // Gemini on nextPageToken. Bounded, because this fills a picker
            // rather than mirroring a catalogue.
            for (page in 0 until MAX_PAGES) {
                val conn = (URL(url).openConnection() as HttpsURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    when (provider) {
                        AiProvider.ANTHROPIC -> {
                            setRequestProperty("x-api-key", apiKey)
                            setRequestProperty("anthropic-version", "2023-06-01")
                        }
                        AiProvider.GEMINI -> setRequestProperty("x-goog-api-key", apiKey)
                        else -> setRequestProperty("authorization", "Bearer $apiKey")
                    }
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                conn.disconnect()
                if (code !in 200..299) {
                    Logger.w("ModelCatalog",
                        "${provider.label} would not list models: HTTP $code; " +
                            body.trim().replace(Regex("\\s+"), " ").take(300)
                                .ifBlank { "(empty)" })
                    return Result.Failed(
                        "${provider.label} would not list its models (HTTP $code). The curated " +
                            "list is still there, and “Other” still takes a typed identifier.")
                }
                bodies += body
                val next = nextPage(provider, base, body) ?: break
                url = next
            }
            parse(provider, bodies)
        }.getOrElse {
            Logger.w("ModelCatalog",
                "${provider.label} model list failed: ${it.javaClass.simpleName}: ${it.message}")
            Result.Failed("Could not reach ${provider.label}: " +
                "${it.message ?: it.javaClass.simpleName}.")
        }
    }

    /** The next page's URL, or null when this was the last one. */
    private fun nextPage(provider: AiProvider, base: String, body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return when (provider) {
            AiProvider.ANTHROPIC -> {
                if (!root.optBoolean("has_more")) null
                else root.optString("last_id").takeIf { it.isNotBlank() }
                    ?.let { "$base&after_id=$it" }
            }
            AiProvider.GEMINI -> root.optString("nextPageToken").takeIf { it.isNotBlank() }
                ?.let { "$base&pageToken=$it" }
            else -> null
        }
    }

    /**
     * One parser for six services, because the differences are shallow: an
     * array under `data` or under `models`, an identifier under `id` or
     * `name`, a human name under one of four keys. Written defensively rather
     * than per-service, so a field being renamed costs a nicer label and not
     * an empty list.
     */
    private fun parse(provider: AiProvider, bodies: List<String>): Result {
        val entries = ArrayList<JSONObject>()
        for (body in bodies) {
            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val arr: JSONArray = root.optJSONArray("data") ?: root.optJSONArray("models") ?: continue
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries += it }
        }
        if (entries.isEmpty())
            return Result.Failed("${provider.label} answered with no model list in it.")

        val all = ArrayList<Pair<String, String>>()
        val visual = ArrayList<Pair<String, String>>()
        val freeIds = HashSet<String>()
        var sawModality = false

        for (m in entries) {
            // Gemini names models "models/gemini-3.6-flash"; the request wants
            // the bare identifier.
            val rawId = m.optString("id").ifBlank { m.optString("name") }
            if (rawId.isBlank()) continue
            val id = rawId.removePrefix("models/")
            val shown = listOf("display_name", "displayName", "name", "description")
                .map { m.optString(it) }
                .firstOrNull { it.isNotBlank() && it != rawId } ?: id
            val entry = id to (if (shown == id) id else "$shown  ($id)")
            all += entry

            var takesImages: Boolean? = null

            m.optJSONObject("architecture")?.optJSONArray("input_modalities")?.let { mods ->
                sawModality = true
                takesImages = (0 until mods.length()).any { mods.optString(it) == "image" }
            }
            m.optJSONObject("capabilities")?.let { c ->
                if (c.has("vision")) { sawModality = true; takesImages = c.optBoolean("vision") }
            }
            m.optJSONArray("supportedGenerationMethods")?.let { methods ->
                // Gemini does not publish modality here, but it does say which
                // models can be asked for content at all - an embedding model
                // in the picker is worse than none.
                sawModality = true
                val gen = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                takesImages = gen && !id.contains("embedding") && !id.contains("aqa")
            }
            // FREE IS READ FROM THE PRICE, not from the ":free" suffix. The
            // suffix is a naming convention OpenRouter may keep or drop; a
            // prompt and completion price of zero is what actually decides
            // whether a request costs anything, and it is the one field that
            // tracks the lineup as it rotates.
            val free = m.optJSONObject("pricing")?.let { pr ->
                val p0 = pr.optString("prompt").toDoubleOrNull()
                val c0 = pr.optString("completion").toDoubleOrNull()
                p0 != null && c0 != null && p0 == 0.0 && c0 == 0.0
            } ?: false
            if (free) freeIds += id
            if (takesImages == true) visual += entry
        }

        if (all.isEmpty()) return Result.Failed("${provider.label} listed no models.")

        // Image-capable first, because that is what this app needs, but the
        // rest still appear - marked, not removed.
        val seeing = visual.map { it.first }.toSet()
        val ordered = if (sawModality)
            all.sortedWith(compareBy({ if (it.first in seeing) 0 else 1 }, { it.first }))
        else all.sortedBy { it.first }
        val marked = ordered.map { (id, label) ->
            val l = if (sawModality && id !in seeing) "$label  [text only]" else label
            id to (if (id in freeIds) "$l  [free]" else l)
        }
        Logger.i("ModelCatalog", "${provider.label} listed ${all.size} model(s)" +
            (if (sawModality) "; ${visual.size} can be asked about a picture" else
                "; the service does not say which read images") +
            (if (freeIds.isNotEmpty()) "; ${freeIds.size} cost nothing per request" else ""))
        return Result.Ok(marked, if (sawModality) visual.size else null)
    }
}
