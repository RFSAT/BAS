package com.rfsat.bas.i18n

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.rfsat.bas.log.Logger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

/**
 * Translates the interface at runtime and remembers the result.
 *
 * WHY NOT PER-LANGUAGE RESOURCE FILES. That is the usual Android answer and it
 * is the better one for an app written with translation in mind. BAS is not:
 * of roughly 570 user-visible strings, 22 are in strings.xml and the rest are
 * literals in layouts and Kotlin. Externalising all of them, then maintaining
 * 24 translations by hand, is a large change with a large surface for mistakes
 * — and a wrong translation of "windage" is worse than none.
 *
 * So this works on the RENDERED VIEWS instead: every string the app shows is
 * translated once, cached on the phone, and applied as each screen appears.
 * The network is needed only when a language is first chosen.
 *
 * WHAT IS NEVER TRANSLATED. Units and proper names — MOA, MRAD, hPa, m/s,
 * Kestrel, GoPro, RTSP — are protected, because a translator turns them into
 * words. Numbers, tables and anything already in the target language are left
 * alone.
 */
object Translator {

    private const val TAG = "Translator"
    private const val PREFS = "bas_language"
    private const val ENDPOINT = "https://translation.googleapis.com/language/translate/v2"

    /** Left in English wherever they appear, alone or inside a sentence. */
    private val PROTECTED = listOf(
        "BAS", "MOA", "MRAD", "hPa", "m/s", "km/h", "RH", "ASL", "FPS",
        "Kestrel", "GoPro", "TACTACAM", "ShotKam", "Vortex", "Leica", "SIG",
        "Vectronix", "Terrapin", "FIRE4000", "Netatmo", "Open-Meteo",
        "OpenWeatherMap", "Windy", "RTSP", "MJPEG", "Wi-Fi", "Bluetooth",
        "ISSF", "NRA", "IPSC", "IDPA", "OpenAI", "Claude"
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun language(c: Context): Language = Languages.byCode(prefs(c).getString("code", null))
    fun isSource(c: Context): Boolean = language(c).code == Languages.SOURCE.code

    /** Where translation comes from. On-device is free and works offline once
     *  the model is present, so it is the default; the cloud path stays for a
     *  phone without Play services, or when a second opinion is wanted. */
    enum class Provider(val label: String) {
        ON_DEVICE("On device — free, works offline"),
        CLOUD("Google Cloud Translation — needs a key")
    }

    fun provider(c: Context): Provider =
        runCatching { Provider.valueOf(prefs(c).getString("provider", null) ?: "") }
            .getOrDefault(Provider.ON_DEVICE)

    fun setProvider(c: Context, p: Provider) = prefs(c).edit().putString("provider", p.name).apply()

    fun apiKey(c: Context): String = prefs(c).getString("key", "") ?: ""
    fun setApiKey(c: Context, v: String) = prefs(c).edit().putString("key", v.trim()).apply()

    /**
     * Bumped on every language change. Screens compare it on resume and
     * re-inflate when it moves.
     *
     * ENGLISH IS NEVER TRANSLATED BACK. The cache is keyed by the ORIGINAL
     * English string, and translation happens only when a view is rendered —
     * so returning to English is not a reverse translation, it is simply the
     * app drawing its own text again with nothing applied. That is also why a
     * screen must be re-inflated rather than patched: a view already showing
     * translated text has lost the original, and only re-inflation gets it
     * back from the layout.
     */
    @Volatile var epoch: Int = 0
        private set

    fun setLanguage(c: Context, lang: Language) {
        prefs(c).edit().putString("code", lang.code).apply()
        TranslationStore.load(c, lang.code)
        epoch++
    }

    fun init(c: Context) = TranslationStore.load(c, language(c).code)

    /** A cached translation, or the original. Never blocks and never calls out:
     *  a screen must render whether or not anything has been translated. */
    fun t(text: CharSequence?): CharSequence? {
        val s = text?.toString() ?: return text
        if (s.isBlank()) return text
        return TranslationStore.get(s) ?: text
    }

    /**
     * Apply the cached translation to every view under [root]. Runs on the UI
     * thread, touches only text that has a translation, and marks each view so
     * a second pass cannot translate an already-translated string.
     */
    fun apply(root: View?) {
        if (root == null || TranslationStore.size() == 0) return
        runCatching { walk(root) }
    }

    private fun walk(v: View) {
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        if (v is TextView) {
            if (v.getTag(TAG_DONE) == true) return
            val src = v.text?.toString()
            if (!src.isNullOrBlank() && translatable(src)) {
                TranslationStore.get(src)?.let { v.text = it }
            }
            val hint = v.hint?.toString()
            if (!hint.isNullOrBlank() && translatable(hint)) {
                TranslationStore.get(hint)?.let { v.hint = it }
            }
            v.setTag(TAG_DONE, true)
        }
    }

    private const val TAG_DONE = 0x7f5a0001

    /** Numbers, tables and bare identifiers are not language. */
    private fun translatable(s: String): Boolean {
        if (s.length < 3) return false
        if (!s.any { it.isLetter() }) return false
        if (PROTECTED.contains(s.trim())) return false
        // A monospaced data row — the conditions table, the shot list — is
        // alignment, not prose; translating it would break the columns.
        val digits = s.count { it.isDigit() }
        return digits <= s.length / 2
    }

    // ---- the network half -------------------------------------------------

    data class Progress(val done: Int, val total: Int)

    /**
     * Translate the whole corpus into [lang], in batches, caching as it goes.
     * [onProgress] is called on a worker thread; [onDone] reports success and a
     * message fit to show.
     */
    fun translateAll(
        context: Context,
        lang: Language,
        corpus: List<String>,
        onProgress: (Progress) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        if (lang.code == Languages.SOURCE.code) {
            setLanguage(context, lang); onDone(true, "Showing the original English."); return
        }
        val useCloud = provider(context) == Provider.CLOUD
        val key = apiKey(context)
        if (useCloud && key.isBlank()) {
            onDone(false, "A Google Cloud Translation key is needed for that provider — or switch to on-device, which is free.")
            return
        }

        Thread {
            TranslationStore.load(context, lang.code)
            val missing = corpus.filter { !TranslationStore.has(it) }
            if (missing.isEmpty()) {
                setLanguage(context, lang)
                onDone(true, "Already translated — ${TranslationStore.size()} phrases, no network needed.")
                return@Thread
            }
            Logger.i(TAG, "translating ${missing.size} phrases into ${lang.english}")
            var done = 0
            var failed = 0
            for (batch in missing.chunked(64)) {
                val ok = runCatching {
                    if (useCloud) translateBatch(batch, lang.code, key)
                    else translateBatchOnDevice(batch, lang.code)
                }
                    .onFailure { Logger.e(TAG, "batch failed", it) }
                    .getOrNull()
                if (ok == null) { failed++; if (failed >= 3) break else continue }
                for ((src, out) in batch.zip(ok)) TranslationStore.put(src, out)
                done += batch.size
                onProgress(Progress(done, missing.size))
            }
            closeOnDevice()
            TranslationStore.save(context, lang.code)
            if (done == 0) {
                onDone(false, "Nothing could be translated — check the key and the connection. The Log has the detail.")
            } else {
                setLanguage(context, lang)
                onDone(true, "${lang.native}: $done phrases translated and saved. No connection is needed from now on.")
            }
        }.start()
    }

    /**
     * On-device translation through ML Kit. The language model is fetched once
     * — that is the only moment a connection is needed — and everything after
     * runs on the phone, at no cost and with no key.
     *
     * The client is opened once per language and closed by [closeOnDevice], not
     * per string: creating one per phrase would download-check 570 times.
     */
    private var onDeviceClient: com.google.mlkit.nl.translate.Translator? = null
    private var onDeviceCode: String? = null

    private fun onDeviceFor(code: String): com.google.mlkit.nl.translate.Translator {
        val existing = onDeviceClient
        if (existing != null && onDeviceCode == code) return existing
        closeOnDevice()
        val target = com.google.mlkit.nl.translate.TranslateLanguage.fromLanguageTag(code)
            ?: throw IllegalStateException("ML Kit does not support '$code'")
        val opts = com.google.mlkit.nl.translate.TranslatorOptions.Builder()
            .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
            .setTargetLanguage(target)
            .build()
        val client = com.google.mlkit.nl.translate.Translation.getClient(opts)
        // Any network: a shooter choosing a language at home on mobile data
        // should not be told to find Wi-Fi.
        com.google.android.gms.tasks.Tasks.await(
            client.downloadModelIfNeeded(
                com.google.mlkit.common.model.DownloadConditions.Builder().build()))
        onDeviceClient = client
        onDeviceCode = code
        Logger.i(TAG, "on-device model ready for $code")
        return client
    }

    fun closeOnDevice() {
        runCatching { onDeviceClient?.close() }
        onDeviceClient = null
        onDeviceCode = null
    }

    private fun translateBatchOnDevice(batch: List<String>, target: String): List<String> {
        val client = onDeviceFor(target)
        return batch.map { src ->
            val out = com.google.android.gms.tasks.Tasks.await(client.translate(mask(src)))
            unmask(out)
        }
    }

    /** Google Cloud Translation v2. Protected terms are masked before the call
     *  and restored after, so a unit cannot come back as a word. */
    private fun translateBatch(batch: List<String>, target: String, key: String): List<String> {
        val masked = batch.map { mask(it) }
        val body = StringBuilder("target=").append(target).append("&format=text")
        for (m in masked) body.append("&q=").append(URLEncoder.encode(m, "UTF-8"))
        val c = (URL("$ENDPOINT?key=$key").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 20000; requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(200)}")
        val arr = JSONObject(text).getJSONObject("data").getJSONArray("translations")
        return (0 until arr.length()).map { unmask(arr.getJSONObject(it).getString("translatedText")) }
    }

    private fun mask(s: String): String {
        var out = s
        PROTECTED.forEachIndexed { i, term -> out = out.replace(term, "␂$i␃") }
        return out
    }

    private fun unmask(s: String): String {
        var out = s
        PROTECTED.forEachIndexed { i, term -> out = out.replace("␂$i␃", term) }
        return out
    }
}
