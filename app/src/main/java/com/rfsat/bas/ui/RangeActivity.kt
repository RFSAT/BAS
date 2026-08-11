package com.rfsat.bas.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.rfsat.bas.databinding.ActivityRangeBinding
import com.rfsat.bas.results.AnalysisSession
import com.rfsat.bas.scoring.ScoringSession

/**
 * Range mode: a full-screen, high-contrast glance of the correction and score
 * so a prone shooter can read it from arm's length without touching the phone.
 * Polls the live session state while foreground and (if enabled) speaks each
 * update. No bottom nav — one big Close button is the only control.
 */
class RangeActivity : BaseActivity() {

    /** Which correction the glance screen is showing. */
    private enum class Src { GROUPING, BALLISTICS, KESTREL }
    private var src = Src.BALLISTICS

    private lateinit var binding: ActivityRangeBinding
    private val handler = Handler(Looper.getMainLooper())
    private var lastSpokenShots = -1
    private val poll = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRangeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        runCatching { ScoringSession.attach(this) }
        binding.btnRangeClose.setOnClickListener { finish() }
        binding.btnSrcGrouping.setOnClickListener { src = Src.GROUPING; refresh() }
        binding.btnSrcBallistics.setOnClickListener { src = Src.BALLISTICS; refresh() }
        binding.btnSrcKestrel.setOnClickListener { src = Src.KESTREL; refresh() }
        refresh()
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll) }

    private fun refresh() {
        runCatching {
            val corr = ScoringSession.correction(this)
            val useMoa = runCatching {
                com.rfsat.bas.profiles.ProfileRepository(this).getScope().clickUnitIsMoa
            }.getOrDefault(false)
            binding.tvRangeCorrection.text = Corrections.scoringBig(corr, useMoa)
            binding.tvRangeCorrAngle.text = Corrections.scoringCaption(corr)
            val res = ScoringSession.result(this)
            binding.tvRangeScore.text =
                if (res.maxScore > 0) "${res.displayTotal} / ${"%.0f".format(res.maxScore)}" else res.displayTotal
            when (src) {
                Src.BALLISTICS -> {
                    val adj = AnalysisSession.adjustment
                    binding.tvRangeWind.text = if (adj != null) Corrections.ballisticWindageBig(adj) else "—"
                    binding.tvRangeWindCaption.text =
                        if (adj != null) Corrections.ballisticWindageCaption(adj) else "WINDAGE — ballistics"
                    binding.tvRangeElev.text = if (adj != null) Corrections.ballisticElevationBig(adj) else "—"
                    binding.tvRangeElevCaption.text =
                        if (adj != null) Corrections.ballisticElevationCaption(adj) else "ELEVATION — ballistics"
                }
                Src.GROUPING -> {
                    // The shift the GROUP asks for, split the same way.
                    binding.tvRangeWind.text = Corrections.scoringBig(corr, useMoa)
                    binding.tvRangeWindCaption.text = "GROUPING — dial to centre"
                    binding.tvRangeElev.text = Corrections.scoringCaption(corr).ifBlank { "—" }
                    binding.tvRangeElevCaption.text = "CLICKS"
                }
                Src.KESTREL -> {
                    val k = com.rfsat.bas.environment.KestrelBallistics.lastSolution
                    binding.tvRangeWind.text =
                        k?.windageMoa?.let { "%+.2f MOA".format(it) } ?: "—"
                    binding.tvRangeWindCaption.text =
                        if (k != null) "WINDAGE — from the Kestrel" else "WINDAGE — no Kestrel solution yet"
                    binding.tvRangeElev.text =
                        k?.elevationMoa?.let { "%+.2f MOA".format(it) } ?: "—"
                    binding.tvRangeElevCaption.text = "ELEVATION — from the Kestrel"
                }
            }
            val n = ScoringSession.state.shots.size
            binding.tvRangeStatus.text = "$n shots"
            if (n != lastSpokenShots) {
                lastSpokenShots = n
                Speaker.say(this, "${res.displayTotal}. " + Corrections.scoringSpeech(corr))
            }
        }
    }
}
