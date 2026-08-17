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
        AiProvider.ANTHROPIC to listOf(
            "claude-haiku-4-5-20251001" to "Haiku 4.5 — cheapest, fastest",
            "claude-sonnet-5" to "Sonnet 5 — balanced (recommended)",
            "claude-opus-5" to "Opus 5 — most capable, dearest"
        ),
        AiProvider.OPENAI to listOf(
            "gpt-4o-mini" to "GPT-4o mini — cheapest, fastest",
            "gpt-4o" to "GPT-4o — balanced (recommended)"
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
        AiProvider.OPENROUTER to listOf(
            "anthropic/claude-sonnet-5" to "Claude Sonnet 5 (via OpenRouter)",
            "openai/gpt-4o" to "GPT-4o (via OpenRouter)"
        ),
        AiProvider.XAI to listOf(
            "grok-2-vision-latest" to "Grok 2 Vision — reads images"
        ),
        // Gemini renames its models with each generation rather than keeping
        // an alias, so these WILL age. 2.5 is the safe one today and is
        // announced as GA-stable only until 16 October 2026; the 3.x names
        // are current as of this release. "Other" is the escape hatch, and a
        // wrong name here fails as a 404 that says so.
        AiProvider.GEMINI to listOf(
            "gemini-3.5-flash" to "3.5 Flash — fast, cheap (recommended)",
            "gemini-3.1-pro" to "3.1 Pro — strongest reasoning",
            "gemini-2.5-flash" to "2.5 Flash — older, GA until Oct 2026"
        ),
        AiProvider.MISTRAL to listOf(
            "pixtral-large-latest" to "Pixtral Large — vision (recommended)",
            "mistral-medium-latest" to "Mistral Medium — newer, vision"
        ),

        // Unreachable while DEEPSEEK is not offered, kept correct so that
        // re-enabling it does not also resurrect wrong identifiers. These are
        // the models api-docs.deepseek.com lists; the ones shipped in 1.36.0
        // were out of date.
        AiProvider.DEEPSEEK to listOf(
            "deepseek-v4-flash" to "V4 Flash — faster, cheaper",
            "deepseek-v4-pro" to "V4 Pro — more capable"
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
    val FREE_MODELS: Map<AiProvider, List<Pair<String, String>>> = mapOf(
        AiProvider.OPENROUTER to listOf(
            "qwen/qwen2.5-vl-72b-instruct:free" to "Qwen2.5-VL 72B (free, via OpenRouter)",
            "meta-llama/llama-3.2-11b-vision-instruct:free"
                to "Llama 3.2 11B Vision (free, via OpenRouter)"
        )
    )

    val DEFAULT_MODEL: Map<AiProvider, String> = mapOf(
        AiProvider.ANTHROPIC to "claude-sonnet-5",
        AiProvider.OPENAI to "gpt-4o",
        AiProvider.DEEPSEEK to "deepseek-v4-flash",
        AiProvider.OPENROUTER to "anthropic/claude-sonnet-5",
        AiProvider.XAI to "grok-2-vision-latest",
        AiProvider.MISTRAL to "pixtral-large-latest",
        AiProvider.GEMINI to "gemini-3.5-flash"
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

    /** Which list the pickers show: the free one when the shooter asked for
     *  it AND this service has one to give. */
    fun models(p: AiProvider, freeOnly: Boolean = false): List<Pair<String, String>> =
        if (freeOnly && p.freeAccess == FreeAccess.SELECTABLE) FREE_MODELS[p].orEmpty()
        else MODELS[p].orEmpty()

    private const val KEY_FREE = "free_only"

    /**
     * Never true for a service that cannot act on it, whatever is stored.
     *
     * The flag is written per provider, so switching services to compare and
     * back does not silently carry a free-only choice into a paid account —
     * or, worse, leave it set on a service where it means nothing and let the
     * shooter believe requests are free.
     */
    fun freeOnly(context: Context, p: AiProvider): Boolean =
        p.freeAccess == FreeAccess.SELECTABLE &&
            (store(context)?.getBoolean(KEY_FREE + "_" + p.name, false) ?: false)

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
