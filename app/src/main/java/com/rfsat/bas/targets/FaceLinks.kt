package com.rfsat.bas.targets

import com.rfsat.bas.rules.RuleCatalog
import com.rfsat.bas.rules.RuleSet

/**
 * Which competitions are shot on a face, and which face a competition uses.
 *
 * DERIVED, NOT STORED. RuleSet already names its face in targetFaceId, so the
 * reverse direction is a scan of the rule catalogue rather than a second list
 * to keep in step. A stored list would be wrong the first time somebody added
 * a rule set and forgot to update the face — and it would be wrong silently,
 * which is the worst way for a cross-reference to fail.
 *
 * Custom rule sets are included: a shooter who wrote their own club course on
 * the club's face should see it listed against that face.
 */
object FaceLinks {

    /** Competitions shot on this face, built-in and custom alike. */
    fun competitionsFor(faceId: String, custom: List<RuleSet> = emptyList()): List<RuleSet> =
        (RuleCatalog.builtIns + custom).filter { it.targetFaceId == faceId }

    /** The face a competition is shot on, or null when it names one that is
     *  no longer in the catalogue — which is possible for a custom rule set
     *  whose custom face was deleted. */
    fun faceFor(rule: RuleSet, custom: List<TargetFace> = emptyList()): TargetFace? =
        TargetCatalog.byId(rule.targetFaceId)
            ?: custom.firstOrNull { it.id == rule.targetFaceId }

    /** One line for a list row: how many competitions use this face, and the
     *  first of them by name. Long enough to be useful, short enough for a
     *  row that already carries the face's own dimensions. */
    fun summaryFor(faceId: String, custom: List<RuleSet> = emptyList()): String {
        val used = competitionsFor(faceId, custom)
        return when (used.size) {
            0 -> ""
            1 -> used.first().name
            else -> "${used.first().name} and ${used.size - 1} more"
        }
    }
}
