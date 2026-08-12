package com.rfsat.bas.ui

import android.content.Intent
import android.os.Bundle
import android.widget.RadioButton
import com.rfsat.bas.capture.CameraConfig
import com.rfsat.bas.capture.CameraType
import com.rfsat.bas.cloud.CloudSettings
import com.rfsat.bas.databinding.ActivityWelcomeBinding
import com.rfsat.bas.environment.DistanceConfig
import com.rfsat.bas.environment.RangefinderModel

/**
 * First run (and after a full reset): say what BAS is for, ask how it will be
 * used, and configure it from the answers — rather than leaving a new shooter
 * to find the same settings scattered across the Settings screen.
 *
 * Every question has a sensible default already selected, and Skip takes them
 * all, so the wizard can never become an obstacle between someone and the app.
 */
class WelcomeActivity : BaseActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private var step = 0

    private var mode = AppMode.BOTH
    private var camera = CameraType.PHONE
    private var finder = RangefinderModel.MANUAL
    private var weather = false
    private var ai = false

    private class Step(
        val title: String,
        val body: String,
        val options: List<String>,
        val selected: () -> Int,
        val choose: (Int) -> Unit
    )

    private lateinit var steps: List<Step>

    // Kept as constants so the body text stays readable; rendered through
    // Bullets so a wrapped line hangs under the text, not under the mark.
    private val BULLET_BALLISTICS =
        "Ballistics — it measures the crosswind from the shot itself, on video, and turns " +
        "it into the clicks to dial on your turrets."
    private val BULLET_SCORING =
        "Scoring — it registers the target face, finds every hole, and reports the score, " +
        "the group and the sight correction the group implies."

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        buildSteps()

        binding.btnWelcomeNext.setOnClickListener {
            if (step < steps.size - 1) { step++; render() } else completeSetup(applyAll = true)
        }
        binding.btnWelcomeBack.setOnClickListener { if (step > 0) { step--; render() } }
        binding.btnWelcomeSkip.setOnClickListener { completeSetup(applyAll = true) }
        render()
    }

    private fun buildSteps() {
        val modes = AppMode.values()
        val cams = listOf(
            CameraType.PHONE to "The phone's own camera",
            CameraType.GOPRO to "A GoPro (HERO9 or later)",
            CameraType.TACTACAM to "A TACTACAM",
            CameraType.SHOTKAM to "A ShotKam Gen 4",
            CameraType.RTSP to "A digital scope or IP camera (RTSP)")
        val finders = listOf(
            RangefinderModel.MANUAL to "None — I will enter the distance",
            RangefinderModel.KESTREL_BRIDGE to "Through a Kestrel 5700 Elite",
            RangefinderModel.SIG_KILO to "SIG KILO (BDX)",
            RangefinderModel.LEICA to "Leica Geovid / CRF",
            RangefinderModel.VORTEX to "Vortex Fury / Razor",
            RangefinderModel.TERRAPIN to "Vectronix Terrapin-X",
            RangefinderModel.FIRE4000 to "Tangoinnos FIRE4000")

        steps = listOf(
            Step("Welcome to BAS",
                "BAS does two jobs, in the order a shooter needs them.\n\n" +
                BULLET_BALLISTICS + "\n\n" + BULLET_SCORING + "\n\n" +
                "How do you mean to use it? You can change this later.",
                modes.map { "${it.label} — ${it.blurb}" },
                { modes.indexOf(mode) }, { i -> mode = modes[i] }),

            Step("What records the shot?",
                "The ballistics side needs a view of the bullet's trail; the scoring side needs a " +
                "view of the card. The phone alone is enough for both. A dedicated camera gives a " +
                "better picture and can be fetched over its own Wi-Fi.",
                cams.map { it.second },
                { cams.indexOfFirst { c -> c.first == camera }.coerceAtLeast(0) },
                { i -> camera = cams[i].first }),

            Step("How is the distance measured?",
                "Distance drives the whole ballistic solution. Leica, Vortex and SIG units can be " +
                "read through a Kestrel 5700 Elite; others connect directly. Entering it by hand " +
                "always works, and the rig's zero distance is one tap away.",
                finders.map { it.second },
                { finders.indexOfFirst { f -> f.first == finder }.coerceAtLeast(0) },
                { i -> finder = finders[i].first }),

            Step("Do you have a weather meter?",
                "Air temperature, pressure and humidity change the trajectory. A Kestrel meter " +
                "supplies them over Bluetooth; without one BAS uses the phone's own sensors and " +
                "sensible standard values.",
                listOf("No — use the phone's sensors", "Yes — I have a Kestrel"),
                { if (weather) 1 else 0 }, { i -> weather = i == 1 }),

            Step("AI assistance?",
                "BAS can send a photograph of the card to an AI service for a second opinion on " +
                "how many shots are on it — its own measurements stay more precise, but counting " +
                "is what a vision model does better. It is optional, uses your own API key, and " +
                "can be set up later in Settings.",
                listOf("No, use the built-in algorithms", "Yes, I will add a key later"),
                { if (ai) 1 else 0 }, { i -> ai = i == 1 }),

            Step("Ready",
                "That is everything. You can change any of it in Settings, and run this again " +
                "from Settings › Other options.\n\n" +
                "A complete rig is already loaded, so you can shoot straight away.",
                emptyList(), { -1 }, { })
        )
    }

    private fun render() {
        val s = steps[step]
        binding.tvWelcomeTitle.text = s.title
        binding.tvWelcomeStep.text = "Step ${step + 1} of ${steps.size}"
        binding.tvWelcomeBody.text =
            if (step == 0) android.text.SpannableStringBuilder()
                .append(s.body.substringBefore(BULLET_BALLISTICS))
                .append(com.rfsat.bas.ui.Bullets.list(
                    listOf(BULLET_BALLISTICS, BULLET_SCORING), binding.tvWelcomeBody.textSize))
                .append(s.body.substringAfter(BULLET_SCORING))
            else s.body
        binding.rgWelcome.removeAllViews()
        s.options.forEachIndexed { i, label ->
            val rb = RadioButton(this).apply {
                text = label
                textSize = 15f
                id = 1000 + i
                setPadding(paddingLeft, 14, paddingRight, 14)
                isChecked = i == s.selected()
                setOnClickListener { s.choose(i) }
            }
            binding.rgWelcome.addView(rb)
        }
        binding.btnWelcomeBack.isEnabled = step > 0
        binding.btnWelcomeNext.text = if (step == steps.size - 1) "Start using BAS" else "Next"
    }

    private fun completeSetup(applyAll: Boolean) {
        if (applyAll) runCatching {
            SetupConfig.setMode(this, mode)
            SetupConfig.setHasWeatherMeter(this, weather)
            CameraConfig.setType(this, camera)
            DistanceConfig.setModel(this, finder)
            CloudSettings.setEnabled(this, ai)
        }
        SetupConfig.setWelcomeDone(this, true)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
