package com.rfsat.bas.i18n

/**
 * The official languages of the European Union, plus English as the source.
 *
 * English is not a translation target: it is what the app is written in, so
 * selecting it simply switches translation off and costs nothing.
 */
data class Language(
    val code: String,
    val english: String,
    val native: String,
    /** ISO-3166 country whose flag stands for the language in the picker. */
    val country: String
) {
    /** The flag as a regional-indicator pair — no drawable assets, and it
     *  follows the system font rather than needing 24 icons kept in step. */
    val flag: String
        get() = country.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }
            .joinToString("")

    /** "🇬🇷  Ελληνικά (Greek)" — the language names itself first, with the
     *  English name after it so an unfamiliar script is still identifiable. */
    fun label(): String =
        if (code == "en") "$flag  $native" else "$flag  $native ($english)"
}

object Languages {
    val SOURCE = Language("en", "English", "English", "GB")

    /** The 24 official EU languages, Irish and Maltese included. */
    val ALL: List<Language> = listOf(
        SOURCE,
        Language("bg", "Bulgarian", "Български", "BG"),
        Language("hr", "Croatian", "Hrvatski", "HR"),
        Language("cs", "Czech", "Čeština", "CZ"),
        Language("da", "Danish", "Dansk", "DK"),
        Language("nl", "Dutch", "Nederlands", "NL"),
        Language("et", "Estonian", "Eesti", "EE"),
        Language("fi", "Finnish", "Suomi", "FI"),
        Language("fr", "French", "Français", "FR"),
        Language("de", "German", "Deutsch", "DE"),
        Language("el", "Greek", "Ελληνικά", "GR"),
        Language("hu", "Hungarian", "Magyar", "HU"),
        Language("ga", "Irish", "Gaeilge", "IE"),
        Language("it", "Italian", "Italiano", "IT"),
        Language("lv", "Latvian", "Latviešu", "LV"),
        Language("lt", "Lithuanian", "Lietuvių", "LT"),
        Language("mt", "Maltese", "Malti", "MT"),
        Language("pl", "Polish", "Polski", "PL"),
        Language("pt", "Portuguese", "Português", "PT"),
        Language("ro", "Romanian", "Română", "RO"),
        Language("sk", "Slovak", "Slovenčina", "SK"),
        Language("sl", "Slovenian", "Slovenščina", "SI"),
        Language("es", "Spanish", "Español", "ES"),
        Language("sv", "Swedish", "Svenska", "SE")
    )

    fun byCode(code: String?): Language = ALL.firstOrNull { it.code == code } ?: SOURCE
}
