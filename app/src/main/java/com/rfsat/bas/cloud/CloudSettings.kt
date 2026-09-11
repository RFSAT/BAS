package com.rfsat.bas.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rfsat.bas.log.Logger

/**
 * Where the shooter's AI service keys live, and what they are allowed to do.
 *
 * A KEY IS NOT A LOGIN. It is issued from that service's developer console,
 * bills the account it belongs to, and is not the same thing as the password
 * used to sign in to the service's chat product — consumer credentials do not
 * work against the API at all. That distinction is stated in the UI, because
 * getting it wrong is the first thing anyone will do.
 *
 * THREE SEPARATE CHOICES OF SERVICE, because they are separate questions and
 * one shared setting made the app appear to ignore what was picked:
 *
 *   [importProvider]  which service scores a card on import, when the import
 *                     engine is not the app's own.
 *   [opinionProvider] which service the Results screen's second opinion asks.
 *   [setupProvider]   which service the key and model controls in Settings
 *                     are currently editing. Nothing is sent on its account
 *                     unless one of the other two names it.
 *
 * Stored through EncryptedSharedPreferences rather than the ordinary kind: a
 * credential that can spend the user's money should not sit in a plain XML
 * file that a device backup or a rooted process can read. If the keystore is
 * unavailable the key is NOT quietly written in the clear — storage fails,
 * the caller is told, and the feature stays off.
 */
/** What actually scores a card when a photograph is imported.
 *
 *  NOT named ScoringEngine: com.rfsat.bas.scoring.ScoringEngine already is
 *  the object that turns a hole into a score, and two types a letter apart
 *  in the same file is a bug waiting to be written. */
enum class ScoringSource(val label: String) {
    EMBEDDED("Embedded — the app's own algorithms"),
    CLOUD("AI service — it finds and scores")
}

object CloudSettings {

    private const val FILE = "sts_cloud"
    private const val KEY_API = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_OVERRIDE = "override_app"
    private const val KEY_ENGINE = "engine"
    /** The service the Settings key/model controls are editing. Also the
     *  migration source for the two below, which did not exist before. */
    private const val KEY_PROVIDER = "provider"
    private const val KEY_IMPORT_PROVIDER = "provider_import"
    private const val KEY_OPINION_PROVIDER = "provider_opinion"

    /** Offered in the picker. Vision-capable models only.
     *
     *  Deliberately short lists rather than everything either service has
     *  published: a model that cannot see an image, or cannot be held to a
     *  schema, fails in a way the shooter cannot diagnose. "Other" lets a
     *  newer identifier be typed in, so a list going stale strands nobody. */
    val MODELS: Map<AiProvider, List<Pair<String, String>>> = mapOf(
        // Every current Claude model takes images and tools, so the whole
        // published line is offered.
        AiProvider.ANTHROPIC to listOf(
            "claude-haiku-4-5-20251001" to "Haiku 4.5 — cheapest, fastest",
            "claude-sonnet-5" to "Sonnet 5 — balanced (recommended)",
            "claude-opus-5" to "Opus 5 — more capable",
            "claude-fable-5" to "Fable 5 — most capable, dearest"
        ),
        // GPT-4o was the whole list here and is two generations behind. The
        // 5.6 line takes images and answers to a schema throughout.
        AiProvider.OPENAI to listOf(
            "gpt-5.6-luna" to "GPT-5.6 Luna — cheapest, fastest",
            "gpt-5.6-terra" to "GPT-5.6 Terra — balanced (recommended)",
            "gpt-5.6-sol" to "GPT-5.6 Sol — most capable, dearest",
            "gpt-4o" to "GPT-4o — older, kept for accounts still on it"
        ),
        // MODEL IDENTIFIERS GO STALE FASTER THAN ANYTHING ELSE HERE. The
        // DeepSeek entry shipped in 1.36.0 was out of date the day it was
        // written. So these lists are deliberately SHORT and prefer "-latest"
        // style aliases, which the vendors repoint rather than retire, and
        // every picker keeps an "Other" box for typing whatever the account
        // actually has. A wrong identifier here is a 404 naming the model —
        // annoying, obvious, and fixable without an app update.
        // "(via OpenRouter)" on every entry, because OpenRouter is a ROUTER
        // and not a model maker: these identifiers name someone else's model
        // reached through OpenRouter's API, on an OpenRouter key, billed by
        // OpenRouter.
        //
        // The labels used to read "Claude Sonnet 5 — balanced", identical to
        // the entry under the standalone Anthropic service, which left two
        // questions unanswerable from the screen: whose model is this, and who
        // is charging me. Picking Anthropic -> Claude Sonnet 5 and
        // OpenRouter -> Claude Sonnet 5 reaches the same model but needs a
        // different key and spends a different account.
        // OpenRouter carries hundreds of models and the number changes
        // weekly, so a hand-written list can only ever be a starting point.
        // Press "Ask the service" for what the key actually reaches.
        AiProvider.OPENROUTER to listOf(
            "anthropic/claude-sonnet-5" to "Claude Sonnet 5 (via OpenRouter)",
            "anthropic/claude-opus-5" to "Claude Opus 5 (via OpenRouter)",
            "openai/gpt-5.6-terra" to "GPT-5.6 Terra (via OpenRouter)",
            "google/gemini-3.6-flash" to "Gemini 3.6 Flash (via OpenRouter)",
            "mistralai/mistral-medium-latest" to "Mistral Medium 3.5 (via OpenRouter)"
        ),
        // grok-2-vision-latest shipped here and returned "Model not found"
        // from the field. The Grok 2 and Grok 4 families were retired in May
        // 2026 and their identifiers now redirect to 4.3, which is where
        // image understanding lives.
        AiProvider.XAI to listOf(
            "grok-4.3" to "Grok 4.3 — reads images (recommended)",
            "grok-4.5" to "Grok 4.5 — newer, more capable",
            "grok-4.6" to "Grok 4.6 — newest"
        ),
        // Gemini renames its models with each generation rather than keeping
        // an alias, so these WILL age. 2.5 is the safe one today and is
        // announced as GA-stable only until 16 October 2026; the 3.x names
        // are current as of this release. "Other" is the escape hatch, and a
        // wrong name here fails as a 404 that says so.
        // Two of the three entries here were dead in the field: 3.1 Pro
        // returned "not found for API version v1beta", and 2.5 Flash
        // answered "no longer available to new users; use 3.6 Flash". 3.5
        // Flash still works and stays, because it is the one that has been
        // seen to answer correctly on a card.
        // ORDERED BY WHAT SCORED THE TEST CARD. 3.6 and 3.5 Flash both found
        // all fifteen holes in the right places. 3.7 Flash answers 503 "high
        // demand" more often than it answers; Flash-Lite was close but not
        // close enough to accept.
        AiProvider.GEMINI to listOf(
            "gemini-3.6-flash" to "3.6 Flash — correct on the test card (recommended)",
            "gemini-3.5-flash" to "3.5 Flash — also correct on the test card",
            "gemini-3.5-flash-lite" to "3.5 Flash-Lite — cheapest, but less accurate here",
            "gemini-3.7-flash" to "3.7 Flash — newest, but frequently busy (503)"
        ),
        // TWO SEPARATE FAULTS, BOTH SEEN IN THE FIELD.
        //
        // pixtral-large-latest returned {"message":"Invalid model",
        // "type":"invalid_model"} on every request. 1.49.4 guessed that the
        // strict JSON schema was being refused and added a JSON-mode retry;
        // that guess was WRONG, and the log said so plainly once refusals
        // were being logged. Pixtral Large was deprecated in February 2026
        // and retired on 31 May 2026, its vision folded into the main model
        // line. The identifier had simply stopped existing.
        //
        // mistral-medium-latest does answer, and answers badly: asked about a
        // card whose group was in the black, it returned holes marched evenly
        // out from the exact centre in two, and on another attempt four,
        // directions. Left available, labelled for what it does here.
        // ministral-3-14b-latest WAS INVENTED HERE and does not exist. It was
        // written from a docs page listing "Ministral 3 14B" as a model NAME
        // and guessing the alias; Mistral answered invalid_model. Aliases are
        // not derivable from display names, and nothing in this file should be
        // written from a guess again.
        //
        // The three that do exist were all tried on one card and all three
        // invented positions — see AiProvider.MISTRAL for what each did.
        AiProvider.MISTRAL to listOf(
            "mistral-medium-latest" to "Mistral Medium 3.5 — invented positions on the test card",
            "mistral-large-latest" to "Mistral Large 3 — wrong positions on the test card",
            "mistral-small-latest" to "Mistral Small 4 — invented positions on the test card"
        ),

        // Vision arrived on 21 August 2026, which is why DeepSeek is offered
        // now. The text models stay listed because an account may still be
        // pointed at one, and they now fail clearly rather than mysteriously.
        AiProvider.DEEPSEEK to listOf(
            "deepseek-v4-flash-vision-exp" to "V4 Flash Vision — experimental, reads images",
            "deepseek-v4-flash" to "V4 Flash — text only, cannot score a card",
            "deepseek-v4-pro" to "V4 Pro — text only, cannot score a card"
        )
    )

    /**
     * Models reachable without paying, for the one service where that is a
     * choice the app can make.
     *
     * These rotate faster than anything else in this file — OpenRouter adds
     * and withdraws free variants continually — so the list is short, the
     * "Other" box takes anything, and the failure is a 404 naming the model.
     *
     * READ THE CAVEAT IN AiProvider.freeAccessNote. Free models are
     * rate-limited and many do not honour a strict json_schema, which this
     * app depends on. A free model failing where a paid one works is
     * expected behaviour, not a bug in the app.
     */
    /**
     * BOTH ENTRIES THAT USED TO LIVE HERE ARE GONE from OpenRouter's own
     * catalogue: qwen/qwen2.5-vl-72b-instruct:free answered 404 "unavailable
     * for free" and meta-llama/llama-3.2-11b-vision-instruct:free "not found
     * on this account". Checked against the catalogue directly — neither is
     * listed any more.
     *
     * The free lineup rotates constantly, which makes this the one list in
     * this file that CANNOT be kept right by editing it. These are free
     * image-capable models present at the time of writing, and a starting
     * point only: press "Ask the service" and the app discovers whatever is
     * free today from the catalogue's own pricing, marked [free].
     *
     * Free is genuinely free — no card, $0 balance — but capped at 20
     * requests a minute and 50 a DAY unless $10 of credit has been bought at
     * some point, which raises the daily cap to 1000.
     */
    val FREE_MODELS: Map<AiProvider, List<Pair<String, String>>> = mapOf(
        AiProvider.OPENROUTER to listOf(
            "thinkingmachines/inkling:free" to "Inkling (free, via OpenRouter)",
            "thinkingmachines/inkling-small:free" to "Inkling Small (free, via OpenRouter)",
            "dots-studio/dots-3-note-preview:free" to "Dots 3 Note preview (free, via OpenRouter)"
        )
    )

    val DEFAULT_MODEL: Map<AiProvider, String> = mapOf(
        AiProvider.ANTHROPIC to "claude-sonnet-5",
        AiProvider.OPENAI to "gpt-5.6-terra",
        AiProvider.DEEPSEEK to "deepseek-v4-flash-vision-exp",
        AiProvider.OPENROUTER to "anthropic/claude-sonnet-5",
        AiProvider.XAI to "grok-4.3",
        AiProvider.MISTRAL to "mistral-medium-latest",
        AiProvider.GEMINI to "gemini-3.6-flash"
    )

    private var prefs: SharedPreferences? = null

    /**
     * True when the stored keys were found unreadable and thrown away. The
     * caller shows this once so the shooter knows to enter a key again rather
     * than assuming the feature is broken.
     */
    var keysWereReset: Boolean = false
        private set

    private fun store(context: Context): SharedPreferences? {
        prefs?.let { return it }
        open(context)?.let { prefs = it; return it }

        // ---- IT COULD NOT BE OPENED, so it is thrown away and remade ----
        //
        // The file is encrypted with a master key in the Android Keystore, and
        // the Keystore does not survive a restore onto another phone, a
        // factory reset of credentials, or certain OS upgrades. When it goes,
        // the file becomes ciphertext nobody can read — and every later
        // attempt fails the same way, so the AI features would be dead for
        // good with no explanation.
        //
        // Deleting it costs the user re-entering a key, which takes ten
        // seconds. Not deleting it costs them the feature permanently. The
        // file is excluded from backup precisely so this should not happen,
        // but "should not" is not a recovery plan.
        Logger.w("CloudSettings", "the stored keys could not be decrypted; discarding them")
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit()
            context.deleteSharedPreferences(FILE)
        }
        val fresh = open(context)
        if (fresh != null) { prefs = fresh; keysWereReset = true }
        return fresh
    }

    private fun open(context: Context): SharedPreferences? = runCatching {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, FILE, master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.onFailure {
        Logger.e("CloudSettings", "encrypted storage unavailable: ${it.message}")
    }.getOrNull()

    fun clearResetFlag() { keysWereReset = false }

    // A stored choice is passed through offeredOr, so a provider that has
    // since been withdrawn cannot leave a picker with nothing selected.
    private fun read(context: Context, key: String, fallback: AiProvider): AiProvider =
        store(context)?.getString(key, null)
            ?.let { n -> AiProvider.values().firstOrNull { it.name == n } }
            ?.let { AiProvider.offeredOr(it) }
            ?: fallback

    /** The service being edited by the key and model controls in Settings.
     *  A display choice only — it sends nothing on its own. */
    fun setupProvider(context: Context): AiProvider =
        read(context, KEY_PROVIDER, AiProvider.ANTHROPIC)

    fun setSetupProvider(context: Context, value: AiProvider) {
        store(context)?.edit()?.putString(KEY_PROVIDER, value.name)?.apply()
    }

    /** The service that scores a card on import when [engine] is CLOUD.
     *
     *  Falls back to whatever the single old setting held, so an existing
     *  installation keeps the service it was already using. */
    fun importProvider(context: Context): AiProvider =
        read(context, KEY_IMPORT_PROVIDER, setupProvider(context))

    fun setImportProvider(context: Context, value: AiProvider) {
        store(context)?.edit()?.putString(KEY_IMPORT_PROVIDER, value.name)?.apply()
    }

    /** The service the second opinion button asks. Independent of
     *  [importProvider] on purpose: comparing one service's answer against
     *  another's is exactly what a second opinion is for. */
    fun opinionProvider(context: Context): AiProvider =
        read(context, KEY_OPINION_PROVIDER, setupProvider(context))

    fun setOpinionProvider(context: Context, value: AiProvider) {
        store(context)?.edit()?.putString(KEY_OPINION_PROVIDER, value.name)?.apply()
    }

    /** Each service's key is kept separately, so switching back and forth
     *  does not mean pasting a key in again. There is deliberately no
     *  "current key": every caller says which service it means. */

    fun apiKey(context: Context, p: AiProvider): String =
        sanitise(store(context)?.getString(KEY_API + "_" + p.name, "").orEmpty())

    /** False when the key could not be stored SAFELY, which the caller must
     *  report rather than pretend succeeded. */

    fun setApiKey(context: Context, p: AiProvider, value: String): Boolean {
        val st = store(context) ?: return false
        st.edit().putString(KEY_API + "_" + p.name, sanitise(value)).apply()
        return true
    }

    /**
     * Strips EVERY whitespace character, not just the ends.
     *
     * A key pasted from a wrapped display carries a line break in the MIDDLE
     * of it, and trim() leaves that where it is. It then goes into an HTTP
     * header, where a newline is illegal, and the request dies before it is
     * sent — "unexpected char 0x0a at 83 in header value", the 83rd character
     * of "Bearer sk-proj-...", which is a newline 76 characters into the key.
     * No API key of either service contains whitespace, so removing all of it
     * can only help.
     *
     * Applied on the way OUT as well as in, so a key stored by an earlier
     * version is repaired rather than failing for ever.
     */
    /** True when at least one offered service has a key. Asked before
     *  deciding what to default, and before telling the shooter that a key
     *  is what stands between them and a second opinion. */
    fun hasAnyKey(context: Context): Boolean =
        AiProvider.OFFERED.any { apiKey(context, it).isNotBlank() }

    /** The provider a keyless shooter should be pointed at: the only one
     *  whose free tier is a matter of choosing MODELS rather than choosing
     *  an account. A key is still required — OpenRouter authenticates even
     *  its ":free" models — it just costs nothing. */
    fun freeRoute(): AiProvider? =
        AiProvider.OFFERED.firstOrNull { it.freeAccess == FreeAccess.SELECTABLE }

    private fun sanitise(v: String): String = v.filterNot { it.isWhitespace() }

    /**
     * The chosen model, kept SEPARATELY for free and paid.
     *
     * One slot for both would mean ticking the box, choosing a free model,
     * unticking it and silently sending that free model to a paid account —
     * or the reverse, which is the expensive direction.
     */
    private fun modelKey(context: Context, p: AiProvider): String =
        KEY_MODEL + "_" + p.name + if (freeOnly(context, p)) "_free" else ""

    fun model(context: Context, p: AiProvider): String {
        val fallback =
            if (freeOnly(context, p)) FREE_MODELS[p]?.firstOrNull()?.first.orEmpty()
            else DEFAULT_MODEL[p].orEmpty()
        return store(context)?.getString(modelKey(context, p), fallback) ?: fallback
    }

    fun setModel(context: Context, p: AiProvider, value: String) {
        store(context)?.edit()?.putString(modelKey(context, p), value)?.apply()
    }

    // ------------------------------------------------------------------
    // The list the SERVICE gave, which outranks the one written here.
    //
    // Stored as newline-separated "id\tlabel" rather than JSON: it is a flat
    // list of strings, and a format that cannot fail to parse is worth more
    // than one that is tidy.
    private const val KEY_FETCHED = "fetched_models"
    private const val KEY_FETCHED_AT = "fetched_models_at"

    fun setFetchedModels(context: Context, p: AiProvider, models: List<Pair<String, String>>) {
        val flat = models.joinToString("\n") { it.first + "\t" + it.second }
        store(context)?.edit()
            ?.putString(KEY_FETCHED + "_" + p.name, flat)
            ?.putLong(KEY_FETCHED_AT + "_" + p.name, System.currentTimeMillis())
            ?.apply()
    }

    fun fetchedModels(context: Context, p: AiProvider): List<Pair<String, String>> {
        val flat = store(context)?.getString(KEY_FETCHED + "_" + p.name, "").orEmpty()
        if (flat.isBlank()) return emptyList()
        return flat.split("\n").mapNotNull { line ->
            val t = line.indexOf('\t')
            if (t <= 0) null else line.substring(0, t) to line.substring(t + 1)
        }
    }

    fun fetchedAt(context: Context, p: AiProvider): Long =
        store(context)?.getLong(KEY_FETCHED_AT + "_" + p.name, 0L) ?: 0L

    fun clearFetchedModels(context: Context, p: AiProvider) {
        store(context)?.edit()
            ?.remove(KEY_FETCHED + "_" + p.name)
            ?.remove(KEY_FETCHED_AT + "_" + p.name)
            ?.apply()
    }

    /** Which list the pickers show: the free one when the shooter asked for
     *  it AND this service has one to give. */
    fun models(p: AiProvider, freeOnly: Boolean = false): List<Pair<String, String>> =
        if (freeOnly && p.freeAccess == FreeAccess.SELECTABLE) FREE_MODELS[p].orEmpty()
        else MODELS[p].orEmpty()

    /**
     * One row of the model picker: what it is, whether it can be chosen, and
     * why not when it cannot.
     *
     * A model the account cannot reach is SHOWN rather than removed - a
     * missing entry reads as an app that does not support the model, when
     * the truth is a key that does not have it. Shown, greyed, and told why.
     */
    data class ModelOption(
        val id: String,
        val label: String,
        val enabled: Boolean,
        /** What would have to change for this one to be usable, or "". */
        val note: String
    )

    private const val TEXT_ONLY = "[text only]"

    /**
     * The picker's rows: everything the service published for this key, plus
     * everything the built-in list knows about that did not come back.
     *
     * Nothing is offered as selectable that would fail on being pressed - a
     * text-only model cannot answer about a photograph, and a model the key
     * cannot reach answers 404 - but both are visible, because being unable
     * to see a model is worse than being unable to pick it.
     */
    fun modelOptions(context: Context, p: AiProvider, freeOnly: Boolean = false):
        List<ModelOption> {
        if (freeOnly && p.freeAccess == FreeAccess.SELECTABLE) {
            // Prefer what the catalogue said costs nothing TODAY over the
            // seed list, which is the one list here that cannot stay right.
            val free = fetchedModels(context, p).filter { it.second.contains("[free]") }
            val list = if (free.isNotEmpty()) free else FREE_MODELS[p].orEmpty()
            return list.map { ModelOption(it.first, it.second, !it.second.contains(TEXT_ONLY),
                if (it.second.contains(TEXT_ONLY))
                    "This model is free but cannot read a picture." else "") }
        }

        val fetched = fetchedModels(context, p)
        val curated = MODELS[p].orEmpty()
        if (fetched.isEmpty())
            return curated.map { ModelOption(it.first, it.second, true, "") }

        val out = ArrayList<ModelOption>()
        for ((id, label) in fetched) {
            val textOnly = label.contains(TEXT_ONLY)
            out += ModelOption(id, label, !textOnly,
                if (textOnly) "${p.label} lists this model as unable to read a picture, so it " +
                    "cannot score a card." else "")
        }
        val have = fetched.map { it.first }.toSet()
        for ((id, label) in curated) {
            if (id in have) continue
            out += ModelOption(id, "$label  [not on this key]", false,
                "${p.label} did not list this model for the key that is set. It needs a " +
                    "${p.label} key on an account that has this model enabled — check " +
                    "${p.console}. Set that key, then ask again.")
        }
        return out
    }

    /**
     * How a stored model choice stands against the last catalogue the service
     * returned for this key.
     *
     *  - UNCHECKED: nothing chosen, or no catalogue has been fetched, so there
     *    is nothing to check against. Not a warning.
     *  - NOT_LISTED: the identifier is not in the fetched list. It may be brand
     *    new, or it may have been retired — either way it is unconfirmed, and
     *    it is exactly the case that used to surface only as a 404 at the
     *    range. DeepSeek's deepseek-v4-pro would land here if it were ever
     *    withdrawn from the catalogue.
     *  - TEXT_ONLY: listed, but the service marks it unable to read a picture,
     *    so it cannot score a card.
     *  - OK: listed and image-capable as far as the service says.
     */
    enum class ModelHealth { OK, NOT_LISTED, TEXT_ONLY, UNCHECKED }

    /**
     * Judge [chosen] against [fetched], the list the service last returned.
     * PURE — no Context and no network — so it is unit-tested without a
     * device, and so the answer is available the moment a screen opens rather
     * than only after a request fails somewhere with no signal.
     */
    fun modelHealth(chosen: String, fetched: List<Pair<String, String>>): ModelHealth {
        if (chosen.isBlank() || fetched.isEmpty()) return ModelHealth.UNCHECKED
        val hit = fetched.firstOrNull { it.first == chosen } ?: return ModelHealth.NOT_LISTED
        return if (hit.second.contains(TEXT_ONLY)) ModelHealth.TEXT_ONLY else ModelHealth.OK
    }

    /** The stored choice's health for [p], from whatever was last fetched. */
    fun modelHealth(context: Context, p: AiProvider): ModelHealth =
        modelHealth(model(context, p), fetchedModels(context, p))

    /** One sentence a shooter can act on, or "" when nothing is wrong. Pure. */
    fun modelHealthNote(state: ModelHealth, chosen: String, providerLabel: String): String =
        when (state) {
            ModelHealth.OK, ModelHealth.UNCHECKED -> ""
            ModelHealth.NOT_LISTED ->
                "⚠ The chosen model “$chosen” is not in the list $providerLabel " +
                    "last returned for this key. It may be new, or it may have been retired — " +
                    "confirm it now, or pick one from the list, rather than finding out at the range."
            ModelHealth.TEXT_ONLY ->
                "⚠ $providerLabel lists the chosen model “$chosen” as unable to read " +
                    "a picture, so it cannot score a card. Pick an image-capable model."
        }

    /**
     * What the picker should show: the service's own answer where one has
     * been fetched, and the curated list otherwise.
     *
     * The free-models box still wins, because "free" is a choice about
     * BILLING that the fetched list knows nothing about on most services.
     */
    fun models(context: Context, p: AiProvider, freeOnly: Boolean = false):
        List<Pair<String, String>> {
        if (freeOnly && p.freeAccess == FreeAccess.SELECTABLE) return FREE_MODELS[p].orEmpty()
        val fetched = fetchedModels(context, p)
        return if (fetched.isNotEmpty()) fetched else MODELS[p].orEmpty()
    }

    private const val KEY_FREE = "free_only"

    /**
     * Never true for a service that cannot act on it, whatever is stored.
     *
     * The flag is written per provider, so switching services to compare and
     * back does not silently carry a free-only choice into a paid account —
     * or, worse, leave it set on a service where it means nothing and let the
     * shooter believe requests are free.
     */
    fun freeOnly(context: Context, p: AiProvider): Boolean {
        if (p.freeAccess != FreeAccess.SELECTABLE) return false
        val s = store(context) ?: return false
        val key = KEY_FREE + "_" + p.name
        // With no key anywhere, free models are the only ones that could
        // ever answer, so that is what the box starts on. The default is
        // WRITTEN the first time it is asked for, deliberately: were it left
        // implicit, entering a paid key later would silently flip the choice
        // and start spending credit on a request the shooter still believed
        // was free.
        if (!s.contains(key)) {
            val start = !hasAnyKey(context)
            s.edit().putBoolean(key, start).apply()
            return start
        }
        return s.getBoolean(key, false)
    }

    fun setFreeOnly(context: Context, p: AiProvider, value: Boolean) {
        store(context)?.edit()?.putBoolean(KEY_FREE + "_" + p.name, value)?.apply()
    }

    fun enabled(context: Context): Boolean =
        store(context)?.getBoolean(KEY_ENABLED, false) ?: false

    fun setEnabled(context: Context, value: Boolean) {
        store(context)?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }

    /**
     * Let the AI answer win outright — both WHETHER there is a shot and
     * WHERE it is.
     *
     * OFF by default, and the cost is worth stating plainly rather than
     * burying: a vision model places a hole to a few per cent of the image,
     * several millimetres on a 170 mm card, where the app measures one it can
     * see to between 0.2 and 1.7 mm. On a 10 m air pistol face the rings are
     * 8 mm apart, so a position taken from the model can be a ring out.
     *
     * What it buys is the other half: the app's measured failure is
     * over-detection — printing read as shots — and counting is the one thing
     * the model does better. With this on, that is fixed without asking.
     *
     * Shots placed this way are recorded as HAND-PLACED, because that is what
     * they are: their position was not measured. A report can then never show
     * them as though it had been.
     */
    fun overrideApp(context: Context): Boolean =
        store(context)?.getBoolean(KEY_OVERRIDE, false) ?: false

    fun setOverrideApp(context: Context, value: Boolean) {
        store(context)?.edit()?.putBoolean(KEY_OVERRIDE, value)?.apply()
    }

    /**
     * Which engine runs when a photograph is imported.
     *
     * This replaced three overlapping checkboxes — "enable the button",
     * "override the app" and "find and score outright" — that had accumulated
     * one request at a time and between them described states nobody wanted,
     * such as overriding an engine that was not running. There is one choice
     * now, and [overrideApp] is what it means under EMBEDDED only.
     *
     *   EMBEDDED runs the app's own detection. The AI service chosen for the
     *   second opinion is available on the Results screen, advisory unless
     *   [overrideApp].
     *
     *   CLOUD does not run the app's hole finding at all. Registration is
     *   still the app's, because without knowing where the card is and how
     *   big it is there is no millimetre grid and nothing can be drawn in the
     *   right place. The picture sent is the RECTIFIED card, already on that
     *   grid, so the marks land exactly where the shooter sees them.
     *
     * Falls back to EMBEDDED when no key is set, rather than importing a
     * photograph and scoring nothing.
     */
    fun engine(context: Context): ScoringSource {
        val want = engineChoice(context)
        return if (want == ScoringSource.CLOUD &&
            apiKey(context, importProvider(context)).isBlank())
            ScoringSource.EMBEDDED else want
    }

    /** What the shooter actually chose, key or no key. Settings shows this,
     *  so a picker cannot silently spring back to Embedded and leave someone
     *  wondering whether their choice was taken. */
    fun engineChoice(context: Context): ScoringSource =
        store(context)?.getString(KEY_ENGINE, null)
            ?.let { name -> ScoringSource.values().firstOrNull { it.name == name } }
            ?: ScoringSource.EMBEDDED

    fun setEngine(context: Context, value: ScoringSource) {
        store(context)?.edit()?.putString(KEY_ENGINE, value.name)?.apply()
    }

    /** The second opinion button can run: it is switched on and the service
     *  it would ask has a key. */
    fun configured(context: Context): Boolean =
        enabled(context) && apiKey(context, opinionProvider(context)).isNotBlank()

    /** For display. The key itself is never shown or logged. */

    fun maskedKey(context: Context, p: AiProvider): String {
        val k = apiKey(context, p)
        return when {
            k.isBlank() -> "not set"
            k.length < 12 -> "set"
            else -> k.take(7) + "…" + k.takeLast(4)
        }
    }
}
