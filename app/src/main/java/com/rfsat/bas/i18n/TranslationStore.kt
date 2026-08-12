package com.rfsat.bas.i18n

import android.content.Context
import com.rfsat.bas.log.Logger
import org.json.JSONObject
import java.io.File

/**
 * The translated text, kept on the phone.
 *
 * The point of caching is that the network is needed ONCE, when a language is
 * chosen. After that the app is fully translated with no connection at all —
 * which matters on a range, where there often is none.
 */
object TranslationStore {

    private const val TAG = "TranslationStore"
    private var loadedCode: String? = null
    private val map = HashMap<String, String>()

    private fun file(context: Context, code: String) =
        File(context.filesDir, "translations_$code.json")

    fun load(context: Context, code: String) {
        if (loadedCode == code) return
        map.clear()
        loadedCode = code
        if (code == Languages.SOURCE.code) return
        val f = file(context, code)
        if (!f.exists()) return
        runCatching {
            val j = JSONObject(f.readText())
            for (k in j.keys()) map[k] = j.getString(k)
            Logger.i(TAG, "loaded ${map.size} strings for $code")
        }.onFailure { Logger.w(TAG, "cache unreadable for $code: ${it.message}") }
    }

    fun save(context: Context, code: String) {
        if (code == Languages.SOURCE.code) return
        runCatching {
            val j = JSONObject()
            for ((k, v) in map) j.put(k, v)
            file(context, code).writeText(j.toString())
            Logger.i(TAG, "saved ${map.size} strings for $code")
        }.onFailure { Logger.w(TAG, "cache not saved: ${it.message}") }
    }

    fun get(source: String): String? = map[source]
    fun put(source: String, translated: String) { map[source] = translated }
    fun size(): Int = map.size
    fun has(source: String): Boolean = map.containsKey(source)
    fun clear() { map.clear() }
}
