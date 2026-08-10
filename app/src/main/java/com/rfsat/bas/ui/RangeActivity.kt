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
        refresh()
    }

    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(poll) }

    private fun refresh() {
        runCatching {
            val corr = ScoringSession.correction(this)
            binding.tvRangeCorrection.text = Corrections.scoringGlance(corr)
            binding.tvRangeCorrAngle.text = Corrections.scoringAngles(corr)
            val res = ScoringSession.result(this)
            binding.tvRangeScore.text =
                if (res.maxScore > 0) "${res.displayTotal} / ${"%.0f".format(res.maxScore)}" else res.displayTotal
            val adj = AnalysisSession.adjustment
            binding.tvRangeWind.text = if (adj != null) Corrections.ballisticGlance(adj) else "—"
            binding.tvRangeWindAngle.text = if (adj != null) Corrections.ballisticAngles(adj) else ""
            val n = ScoringSession.state.shots.size
            binding.tvRangeStatus.text = "$n shots"
            if (n != lastSpokenShots) {
                lastSpokenShots = n
                Speaker.say(this, "${res.displayTotal}. " + Corrections.scoringSpeech(corr))
            }
        }
    }
}
