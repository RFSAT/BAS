package com.rfsat.bas.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.rfsat.bas.BuildConfig
import com.rfsat.bas.cloud.AiProvider
import com.rfsat.bas.cloud.CloudSettings
import com.rfsat.bas.log.Logger
import com.rfsat.bas.profiles.ProfileRepository
import com.rfsat.bas.profiles.ProfileSet
import com.rfsat.bas.rules.RuleRepository
import com.rfsat.bas.rules.RuleSet
import com.rfsat.bas.targets.TargetFace
import com.rfsat.bas.targets.TargetRepository

/**
 * Export and restore everything the user built up: profile sets, custom
 * target faces and custom rule sets.
 *
 * WHAT IS DELIBERATELY NOT IN THE BACKUP. Recorded sessions. A backup is for
 * moving a setup to a new phone, and restoring it must never overwrite what
 * is on the target in front of you — a restore that silently replaced an
 * in-progress 60-shot match would be a catastrophe with no undo. Sessions are
 * exported individually as a report or CSV from the Results screen, which is
 * also the form anyone actually wants to receive them in.
 *
 * FORMAT. Plain JSON, pretty-printed, with a version field. Pretty-printed
 * because a shooter emailing a backup to themselves should be able to open it
 * and see that it contains what they think it does — and because a format
 * nobody can read is a format nobody can repair.
 */
object AppBackup {

    private const val FORMAT_VERSION = 2

    /**
     * Every settings store this app owns, EXCEPT the ones holding recorded
     * work rather than preferences.
     *
     * sts_session, vtb_last_analysis and sts_crash are deliberately absent: a
     * backup restored onto a second phone should not resurrect a half-scored
     * card or last week's crash. bas_cloud is absent too because it is
     * encrypted and is handled separately below.
     */
    private val SETTING_STORES = listOf(
        "bas_prefs", "bas_units", "bas_theme", "bas_range", "bas_camera",
        "bas_distance", "bas_setup", "bas_import", "bas_environment",
        "bas_orientation", "bas_truing", "bas_labels", "vtb_environment"
    )

    data class Payload(
        val formatVersion: Int = FORMAT_VERSION,
        val appVersion: String = "",
        val exportedAtMs: Long = 0L,
        val profileSets: List<ProfileSet> = emptyList(),
        val customTargets: List<TargetFace> = emptyList(),
        val customRules: List<RuleSet> = emptyList(),

        /**
         * Every preference in every settings store, as store -> key -> value.
         *
         * TYPE-TAGGED STRINGS rather than raw values. SharedPreferences holds
         * booleans, ints, longs, floats and strings; Gson reading back into
         * Map<String, Any?> turns every number into a Double, and putting a
         * Double where an Int is expected throws ClassCastException the next
         * time that preference is read. So each value carries its type: "b:",
         * "i:", "l:", "f:", "s:".
         */
        val settings: Map<String, Map<String, String>> = emptyMap(),

        /**
         * API keys, by provider name.
         *
         * THESE ARE PLAINTEXT IN THE FILE. In the app they live in
         * EncryptedSharedPreferences behind a Keystore key; a backup cannot
         * carry that, because the whole point is to be readable on another
         * phone. So a backup containing keys is a file that anyone holding it
         * can spend money with. The app says so before writing one, and
         * [containsApiKeys] lets a reader see it without scanning for
         * something key-shaped.
         */
        val apiKeys: Map<String, String> = emptyMap(),
        val containsApiKeys: Boolean = false
    )

    /** Reads one preferences store into type-tagged strings. */
    private fun dumpStore(context: Context, name: String): Map<String, String> {
        val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val out = LinkedHashMap<String, String>()
        for ((k, v) in p.all) {
            out[k] = when (v) {
                is Boolean -> "b:$v"
                is Int -> "i:$v"
                is Long -> "l:$v"
                is Float -> "f:$v"
                is String -> "s:$v"
                // Sets and anything else are skipped rather than guessed at:
                // this app stores none, and inventing an encoding for a type
                // nobody writes would be code that is never exercised.
                else -> continue
            }
        }
        return out
    }

    private fun restoreStore(context: Context, name: String, values: Map<String, String>): Int {
        val e = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        var n = 0
        for ((k, tagged) in values) {
            val tag = tagged.substringBefore(':', "")
            val raw = tagged.substringAfter(':', "")
            runCatching {
                when (tag) {
                    "b" -> e.putBoolean(k, raw.toBoolean())
                    "i" -> e.putInt(k, raw.toInt())
                    "l" -> e.putLong(k, raw.toLong())
                    "f" -> e.putFloat(k, raw.toFloat())
                    "s" -> e.putString(k, raw)
                    else -> null
                }
            }.onSuccess { if (it != null) n++ }
        }
        e.apply()
        return n
    }

    /**
     * [includeApiKeys] is the caller's decision and is never assumed: the
     * keys are the one part of a backup that can cost the shooter money if
     * the file goes astray.
     */
    fun export(context: Context, includeApiKeys: Boolean = false): String {
        val keys = if (!includeApiKeys) emptyMap() else
            AiProvider.entries.mapNotNull { p ->
                val k = runCatching { CloudSettings.apiKey(context, p) }.getOrDefault("")
                if (k.isBlank()) null else p.name to k
            }.toMap()

        val payload = Payload(
            settings = SETTING_STORES.associateWith { dumpStore(context, it) }
                .filterValues { it.isNotEmpty() },
            apiKeys = keys,
            containsApiKeys = keys.isNotEmpty(),
            formatVersion = FORMAT_VERSION,
            appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            exportedAtMs = System.currentTimeMillis(),
            profileSets = ProfileRepository(context).getSets(),
            customTargets = TargetRepository(context).customFaces(),
            customRules = RuleRepository(context).customSets()
        )
        Logger.i(
            "AppBackup",
            "Exported ${payload.profileSets.size} set(s), ${payload.customTargets.size} target(s), " +
                "${payload.customRules.size} rule set(s)"
        )
        return GsonBuilder().setPrettyPrinting().create().toJson(payload)
    }

    /**
     * Restores a backup. Returns a human-readable summary — the caller shows
     * it verbatim, so a partial restore is reported as a partial restore
     * rather than as success.
     */
    fun import(context: Context, json: String): String {
        if (json.isBlank()) return "Nothing was pasted."

        val payload = runCatching { Gson().fromJson(json, Payload::class.java) }
            .onFailure { Logger.e("AppBackup", "Backup would not parse", it) }
            .getOrNull()
            ?: return "That is not a readable BAS backup — the JSON could not be parsed."

        if (payload.formatVersion > FORMAT_VERSION) {
            return "This backup was written by a newer version of BAS (format ${payload.formatVersion}, " +
                "this build understands $FORMAT_VERSION). Update the app and try again."
        }

        var sets = 0
        var targets = 0
        var rules = 0
        var prefs = 0
        var keys = 0
        val problems = mutableListOf<String>()

        // Settings first: a profile set restored on top of the old units or
        // theme would be displayed through the wrong ones for a moment.
        payload.settings.forEach { (store, values) ->
            runCatching { prefs += restoreStore(context, store, values) }
                .onFailure { problems += "settings store '$store'" }
        }
        payload.apiKeys.forEach { (name, key) ->
            val p = AiProvider.entries.firstOrNull { it.name == name }
            if (p == null) {
                // A key for a service this build does not know is kept out
                // rather than dropped silently — it may belong to a newer
                // version, and saying so is better than losing it quietly.
                problems += "key for unknown service '$name'"
            } else {
                runCatching { if (CloudSettings.setApiKey(context, p, key)) keys++ }
                    .onFailure { problems += "API key for ${p.label}" }
            }
        }

        val profiles = ProfileRepository(context)
        payload.profileSets.forEach { s ->
            runCatching { profiles.saveSet(s); sets++ }
                .onFailure { problems += "profile set '${s.name}'" }
        }

        val targetRepo = TargetRepository(context)
        payload.customTargets.forEach { t ->
            runCatching { targetRepo.saveCustom(t); targets++ }
                .onFailure { problems += "target '${t.name}'" }
        }

        val ruleRepo = RuleRepository(context)
        payload.customRules.forEach { r ->
            runCatching { ruleRepo.saveCustom(r); rules++ }
                .onFailure { problems += "rule set '${r.name}'" }
        }

        Logger.i("AppBackup", "Restored $sets set(s), $targets target(s), $rules rule set(s), " +
            "$prefs preference(s), $keys key(s)")

        return buildString {
            append("Restored $sets profile set(s), $targets custom target(s) and $rules custom rule set(s).")
            if (prefs > 0) append(" $prefs setting(s) restored.")
            if (keys > 0) append(" $keys API key(s) restored.")
            // Said explicitly, because a backup made before this format
            // carried no settings at all and the shooter would otherwise
            // assume theirs simply failed to apply.
            if (payload.formatVersion < 2) {
                append(" This backup predates settings support, so only profiles, targets and " +
                    "rules were in it.")
            }
            if (problems.isNotEmpty()) {
                append(" These could not be restored: ${problems.joinToString(", ")}.")
            }
        }
    }

    /** Type token kept alive for the list deserialisations R8 cannot see. */
    @Suppress("unused")
    private val keepTokens = listOf(
        TypeToken.getParameterized(List::class.java, ProfileSet::class.java).type,
        TypeToken.getParameterized(List::class.java, TargetFace::class.java).type,
        TypeToken.getParameterized(List::class.java, RuleSet::class.java).type
    )
}
