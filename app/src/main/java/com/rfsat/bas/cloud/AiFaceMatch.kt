package com.rfsat.bas.cloud

import com.rfsat.bas.targets.TargetFace

/**
 * Maps a vision model's free-text answer about WHICH TARGET FACE this is onto
 * a catalogue entry.
 *
 * WHY THE AI IS ASKED THIS AT ALL. Telling one face from another by geometry
 * has repeatedly failed here: the black-to-outer RATIO cannot separate an even
 * 1-10 face from an unevenly-pitched one of the same proportions (1.52.5 put
 * NRA/CMP SR on an ISSF Precision Pistol card), and ring VERIFICATION could not
 * either once every candidate's pitch is normalised to the fitted pitch
 * (1.52.6, withdrawn). Naming a printed face is a CLASSIFICATION, which is what
 * a vision model is actually good at, and it needs none of the millimetre
 * precision the model does not have.
 *
 * WHAT THIS DOES NOT DO. It picks a LABEL. The scale still comes from the ring
 * fit or the four corner taps — the model never touches millimetres. A wrong
 * answer here is visible in the picker and overridable; it cannot silently
 * scale a score.
 *
 * DECLINING IS A RESULT. A model may answer "unknown", or describe the card
 * ("a paper target with concentric rings") without naming a face. Returning
 * null then is correct: the shooter is asked, rather than being handed a
 * confident guess. The same applies when two faces score alike — a face named
 * on a coin toss is worse than no name at all.
 */
object AiFaceMatch {

    /** Distance is the strongest discriminator: 10 m air pistol and 25/50 m
     *  precision pistol share every other word. */
    private const val W_DISTANCE = 3.0

    /** What kind of target it is. Separates rifle from pistol at one distance. */
    private const val W_TYPE = 2.0

    /** Who publishes it. Measured: at 1.0 this was too weak — "ISSF 50 m
     *  Rifle" failed to identify ITSELF because DSB/BDS 50 m Kleinkaliber,
     *  sharing the distance and "rifle", came within the margin. */
    private const val W_BODY = 2.0

    private const val W_OTHER = 0.5

    /**
     * A word that occurs in exactly ONE catalogue face is as discriminating as
     * a distance, whatever kind of word it is, so it is weighted like one.
     *
     * This exists because the practical and steel faces have short names made
     * of words no weighting scheme anticipates: "IDPA target", "IPSC Classic
     * target", "300 mm round steel". Scored as ordinary words they came to 1.5
     * and named nothing — four faces in the catalogue could not identify
     * THEMSELVES. Scored by how rare they are, "classic" separates IPSC
     * Classic from IPSC Mini exactly as "10 m" separates air pistol from
     * precision pistol, and no list of hand-picked words has to grow every
     * time a face is added.
     */
    private const val W_RARE = 3.0

    /** Below this nothing in the answer really named a face. */
    private const val MIN_SCORE = 3.0

    /**
     * The winner must beat the runner-up by this much, or the answer is
     * ambiguous and no face is named.
     *
     * ABSOLUTE, not a ratio. A ratio demands a bigger gap the better the
     * answer matches, which rejected the cases it should be surest of: a full
     * "ISSF 10 m Air Rifle" scores 9 against Air Pistol's 7 — plainly decided
     * by one word — and a 1.3x rule threw it away. One clear distinguishing
     * word is the margin that matters, so the bar is just under one.
     */
    private const val MIN_MARGIN = 1.5

    /**
     * Words that say what kind of target it is.
     *
     * "fire" is deliberately NOT here: it is filler shared by "Slow Fire" and
     * "Timed/Rapid Fire", so weighting it pulled the NRA B-series 25 yd faces
     * together instead of apart.
     */
    private val TYPE_WORDS = setOf(
        "air", "pistol", "rifle", "precision", "rapid", "smallbore",
        "kleinkaliber", "biathlon", "fclass", "power", "sporter", "benchrest",
        "prone", "standing", "slow", "timed"
    )

    private val BODY_WORDS = setOf(
        "issf", "uit", "nra", "cmp", "dsb", "bds", "ibu", "nsra", "bdmp",
        "ipsc", "idpa", "prs"
    )

    private val DISTANCE = Regex("""(\d+)\s*(metres|meters|metre|meter|yards|yard|yds|feet|m|yd|ft)\b""")

    /** "25/50 m" means both 25 m and 50 m, and the catalogue writes it that way. */
    private val PAIRED_DISTANCE = Regex("""(\d+)\s*/\s*(\d+)\s*(metres|meters|metre|meter|yards|yard|yds|feet|m|yd|ft)\b""")

    private fun unit(u: String): String = when {
        u.startsWith("y") -> "yd"
        u.startsWith("f") -> "ft"
        else -> "m"
    }

    /** Lowercase, punctuation to space, distances folded to one spelling. */
    internal fun tokens(text: String): Set<String> {
        var s = text.lowercase()
        s = s.replace("f-class", "fclass").replace("f class", "fclass")
        s = s.replace(Regex("""[^a-z0-9/]+"""), " ")
        s = PAIRED_DISTANCE.replace(s) { m ->
            val u = unit(m.groupValues[3])
            "${m.groupValues[1]}$u ${m.groupValues[2]}$u"
        }
        s = DISTANCE.replace(s) { m -> m.groupValues[1] + unit(m.groupValues[2]) }
        s = s.replace("/", " ")
        return s.split(" ").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    private fun isDistance(t: String) = t.matches(Regex("""\d+(m|yd|ft)"""))

    /** How many catalogue faces each word appears in. */
    internal fun rarity(faces: List<TargetFace>): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (f in faces) for (t in faceTokens(f)) counts[t] = (counts[t] ?: 0) + 1
        return counts
    }

    private fun weight(t: String, counts: Map<String, Int>): Double {
        val base = when {
            isDistance(t) -> W_DISTANCE
            t in TYPE_WORDS -> W_TYPE
            t in BODY_WORDS -> W_BODY
            else -> W_OTHER
        }
        // Rarity RAISES a word, never lowers one: a word in one face alone
        // cannot be less telling than the category it happens to fall in.
        return if (counts[t] == 1) maxOf(base, W_RARE) else base
    }

    /** The tokens that identify a catalogue face. */
    internal fun faceTokens(face: TargetFace): Set<String> =
        tokens(face.name) + tokens(face.discipline) + tokens(face.governingBody)

    /**
     * Score one face against the model's answer. A CONFLICTING DISTANCE is
     * disqualifying rather than merely unhelpful: "10 m air pistol" shares
     * "pistol" with the 25/50 m precision face, and without this the shared
     * word would carry it.
     */
    internal fun score(aiTokens: Set<String>, face: TargetFace, counts: Map<String, Int>): Double {
        val faceTokens = faceTokens(face)
        val aiDistances = aiTokens.filter { isDistance(it) }.toSet()
        val faceDistances = faceTokens.filter { isDistance(it) }.toSet()
        if (aiDistances.isNotEmpty() && faceDistances.isNotEmpty() &&
            aiDistances.intersect(faceDistances).isEmpty()
        ) return 0.0
        return faceTokens.filter { it in aiTokens }.sumOf { weight(it, counts) }
    }

    /**
     * The catalogue face [aiFace] names, or null when the answer named none or
     * could not separate two of them.
     */
    fun match(aiFace: String, faces: List<TargetFace>): TargetFace? {
        if (aiFace.isBlank()) return null
        val ai = tokens(aiFace)
        if (ai.isEmpty() || ai == setOf("unknown")) return null
        val counts = rarity(faces)
        val ranked = faces.map { it to score(ai, it, counts) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        if (best.second < MIN_SCORE) return null
        val runnerUp = ranked.drop(1).firstOrNull()?.second ?: 0.0
        if (best.second - runnerUp < MIN_MARGIN) return null
        return best.first
    }
}
