package com.rfsat.bas.targets

import android.os.Bundle
import android.widget.TextView
import com.rfsat.bas.R
import com.rfsat.bas.databinding.ActivityFaceDetailBinding
import com.rfsat.bas.rules.RuleSet
import com.rfsat.bas.ui.BaseActivity

/**
 * One target face, with room to be read.
 *
 * The catalogue list has to fit many faces on a phone, so its drawing is a
 * thumbnail and its description is one line. That is the right trade for
 * choosing, and the wrong one for checking: a shooter deciding whether the
 * card in their hand is this face needs the ring diameters, not a summary.
 *
 * The competitions are the other half of it. A face on its own does not say
 * what it is for, and a shooter who knows they are shooting a B-8 next week
 * should be able to arrive from either end — the face from the competition,
 * or the competition from the face.
 */
class TargetFaceDetailActivity : BaseActivity() {

    companion object {
        const val EXTRA_FACE_ID = "face_id"
    }

    private lateinit var binding: ActivityFaceDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFaceDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBottomNav(0) // reached from the catalogue, not a tab of its own

        val repo = TargetRepository(this)
        val id = intent.getStringExtra(EXTRA_FACE_ID)
        val face = repo.allFaces().firstOrNull { it.id == id }
        if (face == null) {
            notifyUser("That target face is no longer in the catalogue.")
            finish()
            return
        }
        show(face, repo)
    }

    private fun fmt(v: Double): String =
        if (v == Math.floor(v)) "%.0f".format(v) else "%.1f".format(v)

    private fun show(f: TargetFace, repo: TargetRepository) {
        binding.tvTitle.text = f.name
        binding.tvSubtitle.text = listOf(f.governingBody, f.discipline).filter { it.isNotBlank() }
            .joinToString(" · ")

        binding.plot.face = f
        binding.plot.shots = emptyList()

        binding.tvCaption.text = buildString {
            append("Shot at %s m".format(fmt(f.nominalDistanceM)))
            if (!f.verified) {
                append("   ·   dimensions not verified against the current rulebook")
            }
        }

        // ---- the card itself ----
        val params = buildList {
            add("Card" to "${fmt(f.faceWidthMm)} × ${fmt(f.faceHeightMm)} mm")
            add("Nominal distance" to "${fmt(f.nominalDistanceM)} m")
            if (f.outerRadiusMm > 0) add("Outer ring" to "${fmt(f.outerRadiusMm * 2)} mm")
            if (f.blackDiameterMm > 0) add("Aiming black" to "${fmt(f.blackDiameterMm)} mm")
            if (f.hasInnerTen) add(f.innerTenLabel to "${fmt(f.innerTenDiameterMm)} mm")
            f.ringPitchMm?.let { add("Ring pitch" to "${fmt(it)} mm — decimal scoring available") }
            add("Scoring" to f.scoringMode.name.lowercase().replace('_', ' '))
            if (f.custom) add("Origin" to "your own face")
        }
        fill(binding.tblFace, params)

        // ---- every ring, largest value first ----
        fill(binding.tblRings, f.ringsByValue.map { r ->
            (if (r.value > 0) "Ring ${r.value}" else "Zone") to "${fmt(r.diameterMm)} mm"
        }.ifEmpty { listOf("Rings" to "none — this face is scored as a zone or a plate") })

        // ---- what is shot on it ----
        val used = FaceLinks.competitionsFor(f.id, com.rfsat.bas.rules.RuleRepository(this).customSets())
        binding.boxCompetitions.removeAllViews()
        if (used.isEmpty()) {
            binding.lblCompetitions.text = "Shot in"
            addLine("No competition in the library names this face. That does not mean none " +
                "does — it means none of the rule sets here does.")
        } else {
            binding.lblCompetitions.text =
                if (used.size == 1) "Shot in one competition" else "Shot in ${used.size} competitions"
            for (r in used) addCompetition(r)
        }

        binding.tvNotes.text = f.notes
    }

    private fun fill(table: android.widget.TableLayout, rows: List<Pair<String, String>>) {
        table.removeAllViews()
        for ((name, value) in rows) {
            val row = layoutInflater.inflate(R.layout.item_param_row, table, false)
            row.findViewById<TextView>(R.id.tvParamName).text = name
            row.findViewById<TextView>(R.id.tvParamValue).text = value
            table.addView(row)
        }
    }

    private fun addLine(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f
            setPadding(0, 4, 0, 4)
        }
        binding.boxCompetitions.addView(tv)
    }

    /** A competition, as a link: the name in the accent colour, its course of
     *  fire beneath, and a tap that opens the rule itself. */
    private fun addCompetition(rule: RuleSet) {
        val tv = TextView(this).apply {
            text = "▸  ${rule.name}"
            textSize = 14f
            setTextColor(accentColour())
            setPadding(0, 8, 0, 0)
            setOnClickListener {
                startActivity(
                    android.content.Intent(this@TargetFaceDetailActivity,
                        com.rfsat.bas.rules.RulesActivity::class.java)
                        .putExtra(com.rfsat.bas.rules.RulesActivity.EXTRA_RULE_ID, rule.id)
                )
            }
        }
        val sub = TextView(this).apply {
            text = buildString {
                append("%s · %s m · %d shots".format(
                    rule.governingBody, fmt(rule.distanceM), rule.matchShots))
                if (rule.custom) append(" · yours")
            }
            textSize = 12f
            setPadding(18, 0, 0, 2)
        }
        binding.boxCompetitions.addView(tv)
        binding.boxCompetitions.addView(sub)
    }

    private fun accentColour(): Int {
        val tv = android.util.TypedValue()
        return if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true))
            tv.data else 0xFF7FD1A4.toInt()
    }
}
