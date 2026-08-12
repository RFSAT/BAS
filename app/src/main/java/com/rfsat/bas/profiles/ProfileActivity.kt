package com.rfsat.bas.profiles

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.rfsat.bas.detect.ScaleMode
import android.text.InputType
import com.rfsat.bas.cloud.AiProvider
import com.rfsat.bas.cloud.CloudSettings
import com.rfsat.bas.cloud.ScoringSource
import com.rfsat.bas.detect.ScaleSettings
import com.rfsat.bas.R
import com.rfsat.bas.ui.WrappingNameAdapter
import com.rfsat.bas.backup.AppBackup
import com.rfsat.bas.databinding.ActivityProfileBinding
import com.rfsat.bas.log.LogActivity
import com.rfsat.bas.rules.RulesActivity
import com.rfsat.bas.targets.TargetActivity
import com.rfsat.bas.ui.BaseActivity
import com.rfsat.bas.ui.ThemeManager
import com.rfsat.bas.ui.ThemeMode
import com.rfsat.bas.ui.UnitSystem
import com.rfsat.bas.ui.UnitsManager

/**
 * Settings: display, the active profile set, and the equipment behind it.
 *
 * ONE THING WORTH EXPLAINING. Editing any of the equipment fields clears the
 * active profile-set NAME (see ProfileRepository.saveRifle and friends). That
 * is on purpose. A set is a snapshot; once the live profiles no longer match
 * it, continuing to display its name would be a lie, and a shooter reading
 * "50 m Smallbore" on the Home screen has every right to assume the rifle
 * under it is the one that set describes. Save the edit as a new set, or
 * re-apply the old one.
 */
private const val OTHER_MODEL = "Other\u2026"

class ProfileActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var repo: ProfileRepository

    private var suppressThemeCallback = true

    /**
     * Picks the shooter's own reticle image.
     *
     * COPIED INTO THE APP'S OWN FILES rather than referenced where it sits.
     * A gallery URI's permission does not reliably outlive the process, and a
     * reticle that vanishes after a reboot — on the firing point, with no
     * explanation — is worse than one that was never offered.
     */
    private val pickReticle = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) refreshReticle() else onReticlePicked(uri)
    }

    private fun onReticlePicked(uri: android.net.Uri) {
        val ok = runCatching {
            val dest = java.io.File(filesDir, "reticle.png")
            contentResolver.openInputStream(uri)?.use { input ->
                java.io.FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            val bmp = android.graphics.BitmapFactory.decodeFile(dest.absolutePath)
                ?: throw IllegalStateException("not an image this device can read")
            bmp.recycle()
            ScaleSettings.setReticleFile(this, dest.absolutePath)
            ScaleSettings.setReticle(this, com.rfsat.bas.ui.Reticle.CUSTOM)
            true
        }.getOrElse {
            notifyUser("That image could not be used: ${it.message}")
            false
        }
        if (ok) {
            notifyUser(
                "Your reticle will be drawn over the viewfinder, at the guide size set on the " +
                    "Session tab. It is drawn as it comes — a transparent PNG works best, and " +
                    "its colours are not changed by the theme."
            )
        }
        refreshReticle()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = ProfileRepository(this)

        runCatching { initScreen() }.onFailure {
            notifyUser("Settings failed to load: ${it.message}")
        }
        setupBottomNav(R.id.nav_settings)
        runCatching { makeSectionsCollapsible() }
        runCatching {
            binding.tvSettingsVersion.text =
                "BAS ${com.rfsat.bas.BuildConfig.VERSION_NAME} (build ${com.rfsat.bas.BuildConfig.VERSION_CODE}, ${com.rfsat.bas.BuildConfig.BUILD_TYPE})"
        }
    }

    /**
     * Make each Settings section fold under its heading so the long screen is
     * easy to scan — tap a heading to open it. Done in code by grouping the
     * views that follow each tagged heading, so the layout needs no wrapping.
     * Sections start CLOSED so the long screen can be scanned by heading;
     * tapping one opens it. Every section is now named for what it holds
     * (Rangefinder and distance, Backup and reset, Target faces, Competition
     * rules), which is what makes collapsing safe — 1.12.0 collapsed a
     * catch-all called "Elsewhere" and options genuinely went missing.
     */
    /** Icon for a section, by what its heading says. Monochrome single-path
     *  vectors, tinted from the theme, so all four display modes control them. */
    private fun iconFor(title: String): Int {
        val t = title.lowercase()
        return when {
            t.contains("display") -> R.drawable.ic_sec_display
            t.contains("reticle") -> R.drawable.ic_sec_reticle
            t.contains("camera") -> R.drawable.ic_sec_camera
            t.contains("lens") -> R.drawable.ic_sec_camera
            t.contains("detection") -> R.drawable.ic_sec_detect
            t.contains("ai") -> R.drawable.ic_sec_ai
            t.contains("profile set") -> R.drawable.ic_sec_profiles
            t.contains("firearm") -> R.drawable.ic_sec_firearm
            t.contains("ammunition") -> R.drawable.ic_sec_ammo
            t.contains("optics") || t.contains("sight") -> R.drawable.ic_sec_optics
            t.contains("rangefinder") || t.contains("distance") -> R.drawable.ic_sec_range
            t.contains("weather") -> R.drawable.ic_sec_weather
            t.contains("target") -> R.drawable.ic_sec_targets
            t.contains("rules") -> R.drawable.ic_sec_rules
            t.contains("backup") || t.contains("reset") -> R.drawable.ic_sec_backup
            else -> R.drawable.ic_sec_other
        }
    }

    /**
     * Group each section under its heading: an icon beside the title, and the
     * body inside a rounded panel so one section is visibly separate from the
     * next. Tapping a heading folds it. Built in code from the tagged headings,
     * so the long layout needs no wrapping of its own — and the same panel
     * drawable the Home screen uses keeps the two screens consistent.
     */
    private fun makeSectionsCollapsible() {
        val headers = ArrayList<android.view.View>()
        val renderers = HashMap<String, (Boolean) -> Unit>()
        fun collect(v: android.view.View) {
            if (v.tag == "section") headers.add(v)
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) collect(v.getChildAt(i))
        }
        collect(binding.root)
        val pad = (10 * resources.displayMetrics.density).toInt()
        val gap = (10 * resources.displayMetrics.density).toInt()

        for (header in headers) {
            val parent = header.parent as? android.view.ViewGroup ?: continue
            val start = parent.indexOfChild(header)
            val body = ArrayList<android.view.View>()
            var i = start + 1
            while (i < parent.childCount) {
                val child = parent.getChildAt(i)
                if (child.tag == "section") break
                body.add(child); i++
            }
            val tv = header as? android.widget.TextView
            val title = tv?.text?.toString().orEmpty()

            // icon beside the heading
            runCatching {
                tv?.setCompoundDrawablesRelativeWithIntrinsicBounds(iconFor(title), 0, 0, 0)
                tv?.compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
            }

            // move the body into a rounded panel
            val panel = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                setPadding(pad, pad, pad, pad)
                val lp = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = gap
                layoutParams = lp
            }
            runCatching {
                for (b in body) parent.removeView(b)
                for (b in body) panel.addView(b)
                parent.addView(panel, parent.indexOfChild(header) + 1)
            }

            fun render(open: Boolean) {
                panel.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                tv?.text = (if (open) "▾  " else "▸  ") + title
            }
            renderers[title] = ::render
            header.isClickable = true
            header.setOnClickListener {
                val open = panel.visibility != android.view.View.VISIBLE
                render(open)
                if (title.contains("Profile sets", true))
                    for (linked in listOf("Firearm", "Ammunition", "Optics and Sights"))
                        renderers.keys.firstOrNull { it.contains(linked, true) }
                            ?.let { renderers[it]?.invoke(open) }
            }
            render(false)
        }
    }

    private fun initScreen() {
        // ---- display ----
        binding.spTheme.adapter = adapter(ThemeMode.values().map { it.label })
        binding.spTheme.setSelection(ThemeMode.values().indexOf(ThemeManager.mode()))
        binding.spTheme.onItemSelectedListener = onSelectedIndex { i ->
            if (suppressThemeCallback) return@onSelectedIndex
            val mode = ThemeMode.values()[i]
            if (mode != ThemeManager.mode()) {
                ThemeManager.setMode(this, mode)
                recreate() // the theme is applied in onCreate, so restart the screen
            }
        }

        binding.spUnits.adapter = adapter(UnitSystem.values().map { it.label })
        binding.spUnits.setSelection(UnitSystem.values().indexOf(UnitsManager.system()))
        binding.spUnits.onItemSelectedListener = onSelectedIndex { i ->
            UnitsManager.setSystem(this, UnitSystem.values()[i])
        }

        // ---- AI assistance: THREE separate choices of service ----
        //
        // There used to be one. It was labelled "AI service" and it decided
        // both what scored an import and what the second opinion asked, while
        // every message in the app said "Claude" whichever was picked — so
        // choosing OpenAI looked as though it had been ignored. Each question
        // now has its own picker, each picker says what it governs, and no
        // message names a service the app is not about to call.
        fun refreshCloud() {
            binding.cbCloud.isChecked = CloudSettings.enabled(this)
            // EVERY service's key, not just the selected one. Each is stored
            // separately, and seeing only the current one made it look as
            // though setting a second key had replaced the first.
            binding.tvCloudKeys.text = AiProvider.values().joinToString("\n") { p ->
                "${p.label}: ${CloudSettings.maskedKey(this, p)}"
            }
            binding.tvCloudKey.text =
                "Set key applies to: ${CloudSettings.setupProvider(this).label}"
            binding.tvModelLabel.text =
                "Model for ${CloudSettings.setupProvider(this).label}:"
        }

        // ---- what scores a card on import: the app, or a named service ----
        val engineOptions = listOf(ScoringSource.EMBEDDED.label) +
            AiProvider.values().map { it.label }
        binding.spEngine.adapter = android.widget.ArrayAdapter(
            this, R.layout.spinner_item, engineOptions
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spEngine.setSelection(
            if (CloudSettings.engineChoice(this) == ScoringSource.EMBEDDED) 0
            else 1 + AiProvider.values().indexOf(CloudSettings.importProvider(this))
        )
        binding.spEngine.onItemSelectedListener = onSelectedIndex { i ->
            if (i == 0) {
                CloudSettings.setEngine(this, ScoringSource.EMBEDDED)
                notifyUser("Imports will be scored by the app's own algorithms.")
                return@onSelectedIndex
            }
            val p = AiProvider.values().getOrNull(i - 1) ?: return@onSelectedIndex
            CloudSettings.setImportProvider(this, p)
            CloudSettings.setEngine(this, ScoringSource.CLOUD)
            notifyUser(
                if (CloudSettings.apiKey(this, p).isBlank())
                    "${p.label} needs its own key, from ${p.console}. Until one is set, " +
                        "imports use the embedded algorithms."
                else "Imports will be scored by ${p.label}."
            )
        }

        binding.cbCloud.setOnClickListener {
            val want = binding.cbCloud.isChecked
            val p = CloudSettings.opinionProvider(this)
            if (want && CloudSettings.apiKey(this, p).isBlank()) {
                binding.cbCloud.isChecked = false
                notifyUser("${p.label} has no key — the button would have nothing to call.")
            } else {
                CloudSettings.setEnabled(this, want)
                notifyUser(
                    if (want) "A \u201cSecond opinion\u201d button will appear on the Results screen."
                    else "The second opinion button is hidden."
                )
            }
        }

        // ---- which service the second opinion asks ----
        //
        // Independent of the import choice on purpose: asking the other
        // service is exactly what makes a second opinion worth having.
        binding.spOpinion.adapter = android.widget.ArrayAdapter(
            this, R.layout.spinner_item, AiProvider.values().map { it.label }
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spOpinion.setSelection(
            AiProvider.values().indexOf(CloudSettings.opinionProvider(this)))
        binding.spOpinion.onItemSelectedListener = onSelectedIndex { i ->
            val p = AiProvider.values().getOrNull(i) ?: return@onSelectedIndex
            CloudSettings.setOpinionProvider(this, p)
            refreshCloud()
            notifyUser(
                if (CloudSettings.apiKey(this, p).isBlank())
                    "The second opinion will ask ${p.label}, which needs its own key from " +
                        "${p.console}."
                else "The second opinion will ask ${p.label}."
            )
        }

        binding.cbCloudOverride.isChecked = CloudSettings.overrideApp(this)
        binding.cbCloudOverride.setOnClickListener {
            val on = binding.cbCloudOverride.isChecked
            CloudSettings.setOverrideApp(this, on)
            notifyUser(
                if (on) "The AI answer will be applied without asking. Its positions carry " +
                    "several millimetres, so added shots are marked hand-placed \u2014 check them."
                else "The second opinion will offer changes rather than make them."
            )
        }
        // ---- reticle ----
        binding.spReticle.adapter = android.widget.ArrayAdapter(
            this, R.layout.spinner_item, com.rfsat.bas.ui.Reticle.values().map { it.label }
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spReticle.setSelection(
            com.rfsat.bas.ui.Reticle.values().indexOf(ScaleSettings.reticle()))
        binding.spReticle.onItemSelectedListener = onSelectedIndex { i ->
            val chosen = com.rfsat.bas.ui.Reticle.values().getOrNull(i) ?: return@onSelectedIndex
            if (chosen == com.rfsat.bas.ui.Reticle.CUSTOM &&
                ScaleSettings.reticleFile().isEmpty()
            ) {
                pickReticle.launch("image/*")
                return@onSelectedIndex
            }
            ScaleSettings.setReticle(this, chosen)
            refreshReticle()
        }
        refreshReticle()

        binding.etStreamLensK.setText(
            if (ScaleSettings.lensK() != 0.0) "%.3f".format(ScaleSettings.lensK()) else ""
        )
        binding.etStreamLensK.setOnEditorActionListener { _, _, _ ->
            val text = binding.etStreamLensK.text.toString().trim()
            if (text.isEmpty()) {
                ScaleSettings.setLensK(this, 0.0)
                notifyUser("Live frames are used as they come.")
            } else {
                val k = com.rfsat.bas.detect.LensDistortion.parse(text)
                if (k == null) {
                    notifyUser(
                        "That is not a usable coefficient. Measure one on the Import screen, " +
                            "under Lens distortion, from a photo taken with the same camera."
                    )
                } else {
                    ScaleSettings.setLensK(this, k)
                    notifyUser("Live frames will be straightened with k = %.3f.".format(k))
                }
            }
            false
        }

        wireCameraProfile()
        wireMoreInfo()

        // ---- keys and models, one service at a time ----
        //
        // The model list is rebuilt when the service changes, because an
        // identifier from one means nothing to the other. Keys and model
        // choices are kept per service, so switching to compare the two and
        // back does not mean pasting a key in again.
        fun refreshModels() {
            val p = CloudSettings.setupProvider(this)
            val opts = CloudSettings.models(p).map { it.second } + OTHER_MODEL
            binding.spCloudModel.adapter = android.widget.ArrayAdapter(
                this, R.layout.spinner_item, opts
            ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
            val current = CloudSettings.model(this, p)
            val idx = CloudSettings.models(p).indexOfFirst { it.first == current }
            binding.spCloudModel.setSelection(if (idx >= 0) idx else opts.size - 1)
        }

        binding.spProvider.adapter = android.widget.ArrayAdapter(
            this, R.layout.spinner_item, AiProvider.values().map { it.label }
        ).also { it.setDropDownViewResource(R.layout.spinner_dropdown_item) }
        binding.spProvider.setSelection(
            AiProvider.values().indexOf(CloudSettings.setupProvider(this)))
        binding.spProvider.onItemSelectedListener = onSelectedIndex { i ->
            val chosen = AiProvider.values().getOrNull(i) ?: return@onSelectedIndex
            CloudSettings.setSetupProvider(this, chosen)
            refreshModels()
            refreshCloud()
        }
        refreshModels()
        refreshCloud()
        binding.spCloudModel.onItemSelectedListener = onSelectedIndex { i ->
            val p = CloudSettings.setupProvider(this)
            val list = CloudSettings.models(p)
            val picked = list.getOrNull(i)
            if (picked != null) { CloudSettings.setModel(this, p, picked.first); return@onSelectedIndex }
            // "Other": a list of model names goes stale the week it is
            // written, and being unable to type a newer one would strand
            // anyone whose account has moved on.
            val input = EditText(this).apply { hint = "model identifier" }
            AlertDialog.Builder(this)
                .setTitle("Other ${p.label} model")
                .setMessage("Type the model identifier exactly as the service publishes it. " +
                    "It must be able to read images and to answer against a schema.")
                .setView(input)
                .setPositiveButton("Use") { _, _ ->
                    val v = input.text.toString().trim()
                    if (v.isNotBlank()) {
                        CloudSettings.setModel(this, p, v)
                        notifyUser("${p.label} will use $v.")
                    } else refreshModels()
                }
                .setNegativeButton("Cancel") { _, _ -> refreshModels() }
                .show()
        }
        binding.btnCloudKey.setOnClickListener {
            val target = CloudSettings.setupProvider(this)
            val input = android.widget.EditText(this).apply {
                hint = target.keyHint
                // Visible, not masked: a key pasted blind is a key typed
                // wrong, and the dialog is dismissed the moment it is saved.
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("${target.label} API key")
                .setMessage(
                    "From ${target.console}, not the password you sign in " +
                        "to the chat service with \u2014 they are different and the password will " +
                        "not work. It is stored encrypted on this device and never written to " +
                        "the log."
                )
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val raw = input.text.toString()
                    val v = raw.filterNot { it.isWhitespace() }
                    if (v.isBlank()) { notifyUser("Nothing entered."); return@setPositiveButton }
                    val stripped = raw.length - v.length
                    if (CloudSettings.setApiKey(this, target, v)) {
                        refreshCloud()
                        notifyUser(buildString {
                            append("${target.label} key stored")
                            if (stripped > 0) {
                                // A key pasted from a wrapped display carries
                                // a line break, which cannot go in an HTTP
                                // header and used to fail the request before
                                // it was sent.
                                append(" ($stripped space or line break removed)")
                            }
                            append(". Tick the box above to switch the feature on.")
                        })
                    } else {
                        // Not stored anywhere else: a credential that can spend
                        // money does not go into a plain file as a fallback.
                        notifyUser(
                            "This device would not give the app encrypted storage, so the key has " +
                                "NOT been saved. It will not be kept in plain text as a fallback."
                        )
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnCloudClear.setOnClickListener {
            // Only the selected service's key. Forgetting one should not
            // silently lose the other.
            val p = CloudSettings.setupProvider(this)
            CloudSettings.setApiKey(this, p, "")
            if (AiProvider.values().none { CloudSettings.apiKey(this, it).isNotBlank() }) {
                CloudSettings.setEnabled(this, false)
            }
            refreshCloud()
            notifyUser("${p.label} key forgotten.")
        }

        binding.cbSourceDetect.isChecked = ScaleSettings.sourceDetector()
        binding.cbSourceDetect.setOnClickListener {
            ScaleSettings.setSourceDetector(this, binding.cbSourceDetect.isChecked)
            notifyUser(
                if (binding.cbSourceDetect.isChecked)
                    "Shots will be found in the photograph itself, including inside the black."
                else "Shots will be found in the flattened copy again — shots inside the black " +
                    "aiming mark are likely to be missed."
            )
        }
        binding.cbPuncture.isChecked = ScaleSettings.punctureCheck()
        binding.cbPuncture.setOnClickListener {
            ScaleSettings.setPunctureCheck(this, binding.cbPuncture.isChecked)
            notifyUser(
                if (binding.cbPuncture.isChecked)
                    "A candidate must now get lighter outwards from its centre to count as a " +
                        "shot. Re-detect to see the difference."
                else "Shots will be accepted on size, roundness and contrast alone again."
            )
        }
        binding.cbOutside.isChecked = ScaleSettings.scoreOutsideArea()
        binding.cbOutside.setOnClickListener {
            val on = binding.cbOutside.isChecked
            ScaleSettings.setScoreOutsideArea(this, on)
            if (on && !ScaleSettings.punctureCheck()) {
                // Not a preference to be quietly overridden, but this one
                // combination is genuinely unsafe: the region outside the
                // rings is entirely print, and without the profile test it is
                // exactly where false shots come from.
                ScaleSettings.setPunctureCheck(this, true)
                binding.cbPuncture.isChecked = true
                notifyUser(
                    "Misses will be reported. The puncture test has been switched on with it: " +
                        "everything outside the rings is print, and without that test it is " +
                        "where false shots come from."
                )
            } else {
                notifyUser(
                    if (on) "Shots outside the outermost ring will be reported as misses."
                    else "Only shots inside the scoring area will be reported."
                )
            }
        }
        binding.cbFamily.isChecked = ScaleSettings.ringFamilyFit()
        binding.cbFamily.setOnClickListener {
            ScaleSettings.setRingFamilyFit(this, binding.cbFamily.isChecked)
            notifyUser(
                if (binding.cbFamily.isChecked)
                    "Scale will come from a circle fitted to each ring. Re-register any target " +
                        "already open."
                else "Scale will come from the averaged ring ladder again."
            )
        }
        binding.cbWedge.isChecked = ScaleSettings.wedgeEnabled()
        binding.cbWedge.setOnClickListener {
            ScaleSettings.setWedge(this, binding.cbWedge.isChecked)
            notifyUser(
                if (binding.cbWedge.isChecked)
                    "Ring spacing will be measured along the tilt axis only. Re-register any " +
                        "target already open."
                else "Ring spacing will be measured over the whole ring again."
            )
        }

        binding.spScaleMode.adapter = adapter(ScaleMode.values().map { it.label })
        binding.spScaleMode.setSelection(ScaleMode.values().indexOf(ScaleSettings.mode()))
        binding.spScaleMode.onItemSelectedListener = onSelectedIndex { i ->
            val chosen = ScaleMode.values()[i]
            if (chosen != ScaleSettings.mode()) {
                ScaleSettings.setMode(this, chosen)
                notifyUser(
                    "Scale source set to \u201c${chosen.label}\u201d. Re-register any target " +
                        "already open for it to take effect."
                )
            }
        }

        binding.cbShowLog.isChecked = getSharedPreferences(BaseActivity.PREFS, MODE_PRIVATE)
            .getBoolean(com.rfsat.bas.ui.MainActivity.KEY_SHOW_LOG, true)
        binding.cbShowLog.setOnCheckedChangeListener { _, on ->
            getSharedPreferences(BaseActivity.PREFS, MODE_PRIVATE).edit()
                .putBoolean(com.rfsat.bas.ui.MainActivity.KEY_SHOW_LOG, on).apply()
        }

        binding.cbFullScreen.isChecked = fullScreenEnabled()
        binding.cbFullScreen.setOnCheckedChangeListener { _, on ->
            getSharedPreferences(BaseActivity.PREFS, MODE_PRIVATE).edit().putBoolean("full_screen", on).apply()
            recreate()
        }

        // ---- sets ----
        refreshSets()
        binding.btnApplySet.setOnClickListener { applySelectedSet() }
        binding.btnSaveSet.setOnClickListener { saveCurrentAsSet() }
        binding.btnDeleteSet.setOnClickListener { deleteSelectedSet() }

        // ---- catalogues ----
        binding.btnRifleCatalog.setOnClickListener { showRifleCatalog() }
        binding.btnAmmoCatalog.setOnClickListener { showAmmoCatalog() }
        binding.btnScopeCatalog.setOnClickListener { showScopeCatalog() }

        // ---- enums ----
        binding.spFirearmType.adapter = adapter(FirearmType.values().map { it.label })
        binding.spSightType.adapter = adapter(SightType.values().map { it.label })
        binding.spClickUnit.adapter = adapter(ClickUnit.values().map { it.label })
        binding.spClickUnit.onItemSelectedListener = onSelected { updateClickFieldVisibility() }

        // ---- actions ----
        binding.btnSave.setOnClickListener { saveActiveProfiles() }
        binding.btnRules.setOnClickListener { startActivity(Intent(this, RulesActivity::class.java)) }
        binding.btnLog.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }
        binding.btnTargets.setOnClickListener { startActivity(Intent(this, TargetActivity::class.java)) }
        binding.btnCameraDefaults.setOnClickListener { cameraDefaultsMenu() }
        binding.btnRunSetup.setOnClickListener {
            com.rfsat.bas.ui.SetupConfig.setWelcomeDone(this, false)
            startActivity(Intent(this, com.rfsat.bas.ui.WelcomeActivity::class.java))
            finish()
        }
        binding.btnRangeOptions.setOnClickListener { rangeOptionsDialog() }
        binding.btnRangefinder.setOnClickListener { rangefinderProbe() }
        binding.btnWeatherTier.setOnClickListener {
            val tiers = com.rfsat.bas.environment.WeatherTier.values()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Conditions at the firing point")
                .setSingleChoiceItems(tiers.map { it.label }.toTypedArray(),
                    tiers.indexOf(com.rfsat.bas.environment.WeatherConfig.tier(this))) { d, w ->
                    d.dismiss()
                    com.rfsat.bas.environment.WeatherConfig.setTier(this, tiers[w]); refreshEnvLabels()
                }
                .show()
        }
        binding.btnWeatherDevice.setOnClickListener {
            val devs = com.rfsat.bas.environment.EnvSource.values()
                .filter { it != com.rfsat.bas.environment.EnvSource.PHONE }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Default external device")
                .setSingleChoiceItems(devs.map { it.label }.toTypedArray(),
                    devs.indexOf(com.rfsat.bas.environment.EnvDeviceConfig.source(this)).coerceAtLeast(0)) { d, w ->
                    d.dismiss()
                    com.rfsat.bas.environment.EnvDeviceConfig.setSource(this, devs[w]); refreshEnvLabels()
                }
                .show()
        }
        binding.btnWeatherService.setOnClickListener {
            val svcs = com.rfsat.bas.environment.OnlineService.values()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Online weather service")
                .setSingleChoiceItems(svcs.map { it.label }.toTypedArray(),
                    svcs.indexOf(com.rfsat.bas.environment.WeatherConfig.service(this))) { d, w ->
                    d.dismiss()
                    val svc = svcs[w]
                    com.rfsat.bas.environment.WeatherConfig.setService(this, svc)
                    if (svc.needsKey) {
                        val et = android.widget.EditText(this).apply {
                            setText(com.rfsat.bas.environment.WeatherConfig.key(this@ProfileActivity, svc))
                            hint = svc.keyHint
                        }
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("${svc.label} key")
                            .setMessage("Your own key, kept on this phone. Netatmo goes through the RFSAT proxy and needs no key here.")
                            .setView(et)
                            .setPositiveButton("Save") { _, _ ->
                                com.rfsat.bas.environment.WeatherConfig.setKey(this, svc, et.text.toString())
                                refreshEnvLabels()
                            }
                            .setNegativeButton("Cancel", null).show()
                    }
                    refreshEnvLabels()
                }
                .show()
        }
        binding.btnWeatherPosition.setOnClickListener {
            val et = android.widget.EditText(this).apply {
                hint = "lat, lon (blank = use the phone's location)"
                setText(if (com.rfsat.bas.environment.WeatherConfig.hasPosition(this@ProfileActivity))
                    "${com.rfsat.bas.environment.WeatherConfig.latitude(this@ProfileActivity)}, " +
                    "${com.rfsat.bas.environment.WeatherConfig.longitude(this@ProfileActivity)}" else "")
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Position for forecasts")
                .setView(et)
                .setPositiveButton("Save") { _, _ ->
                    val parts = et.text.toString().split(",")
                    val la2 = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 0.0
                    val lo = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.0
                    com.rfsat.bas.environment.WeatherConfig.setPosition(this, la2, lo)
                    refreshEnvLabels()
                }
                .setNegativeButton("Cancel", null).show()
        }
        binding.btnKestrelProfiles.setOnClickListener { importKestrelProfiles() }
        binding.btnEnvRead.setOnClickListener { readEnvironment() }
        refreshEnvLabels()
        binding.btnRangefinderModel.setOnClickListener {
            com.rfsat.bas.environment.RangefinderUi.chooseModel(
                this, com.rfsat.bas.environment.DistanceConfig.model(this)) { m ->
                com.rfsat.bas.environment.DistanceConfig.setModel(this, m)
                refreshRangefinderLabel()
            }
        }
        binding.btnRangefinderTest.setOnClickListener { testRangefinder() }
        binding.btnRangefinderForget.setOnClickListener {
            com.rfsat.bas.environment.DistanceConfig.clearLock(this)
            notifyUser("Forgotten — the next reading will ask for confirmation again.")
        }
        refreshRangefinderLabel()
        binding.btnBackup.setOnClickListener { exportBackup() }
        binding.btnRestore.setOnClickListener { importBackup() }
        binding.btnReset.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset to defaults?")
                .setMessage("This returns BAS to factory settings: equipment, cameras, rangefinder, " +
                    "range options, display and your saved sets, targets and rules all go back to " +
                    "how the app shipped. Export a backup first if you want to keep any of it.")
                .setPositiveButton("Reset everything") { _, _ ->
                    factoryReset(); loadProfilesIntoFields()
                    notifyUser("Reset to defaults. The welcome screen will appear on the next start.")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        loadProfilesIntoFields()
        suppressThemeCallback = false
    }

    override fun onResume() {
        super.onResume()
        setupBottomNav(R.id.nav_settings)
    }

    // ------------------------------------------------------------------
    //  Fields
    // ------------------------------------------------------------------

    private fun loadProfilesIntoFields() {
        val r = repo.getRifle()
        val b = repo.getBullet()
        val s = repo.getScope()

        binding.etRifleName.setText(r.name)
        binding.spFirearmType.setSelection(FirearmType.values().indexOf(r.firearmType))
        binding.etCaliber.setText(r.caliberLabel)
        binding.etBarrel.setText(num(r.barrelLengthIn))
        binding.etTwist.setText(num(r.twistRateInPerTurn))
        binding.etSightHeight.setText(num(r.sightHeightIn))
        binding.etZero.setText(num(r.zeroDistanceM))

        binding.etBulletName.setText(b.name)
        binding.etDiameter.setText(num(b.caliberDiameterIn))
        binding.etWeight.setText(num(b.weightGrains))
        binding.etMv.setText(num(b.muzzleVelocityFps))
        binding.etBc.setText(num(b.ballisticCoefficientG1))
        binding.tvPowerFactor.text =
            "Power factor ${"%.0f".format(b.powerFactor)} — IPSC Major starts at 320 for handgun."

        binding.etScopeName.setText(s.name)
        binding.spSightType.setSelection(SightType.values().indexOf(s.sightType))
        binding.spClickUnit.setSelection(ClickUnit.values().indexOf(s.clickUnit))
        binding.etClickMm.setText(num(s.clickMmAtReference))
        binding.etClickRef.setText(num(s.clickReferenceDistanceM))
        binding.etSightRadius.setText(num(s.sightRadiusMm))
        binding.etElevTravel.setText(num(s.maxElevationTravelMoa))
        binding.etWindTravel.setText(num(s.maxWindageTravelMoa))
        binding.cbInvertElev.isChecked = s.invertElevationDirection
        binding.cbInvertWind.isChecked = s.invertWindageDirection

        updateClickFieldVisibility()
        updateClickSummary()
    }

    /** The mm/reference pair is meaningless for an angular click unit, so it
     *  is hidden rather than left on screen inviting a value that will be
     *  ignored. */
    private fun updateClickFieldVisibility() {
        val unit = ClickUnit.values().getOrNull(binding.spClickUnit.selectedItemPosition)
        val show = unit == ClickUnit.MM_AT_REFERENCE
        val vis = if (show) View.VISIBLE else View.GONE
        binding.lblClickMm.visibility = vis
        binding.etClickMm.visibility = vis
        binding.lblClickRef.visibility = vis
        binding.etClickRef.visibility = vis
        updateClickSummary()
    }

    private fun updateClickSummary() {
        val s = buildScopeFromFields()
        binding.tvClickSummary.text = if (!s.hasClicks) {
            "This sight is recorded as having no usable clicks, so corrections will be given as a " +
                "physical sight movement (if a sight radius is set) or as a distance on the target."
        } else {
            "One click = %.4f MRAD = %.4f MOA. At 10 m that moves the impact %.2f mm; at 50 m, %.1f mm; at 100 m, %.1f mm."
                .format(
                    s.clickMrad, s.clickMrad * ScopeProfile.MOA_PER_MRAD,
                    s.clickMrad * 10.0, s.clickMrad * 50.0, s.clickMrad * 100.0
                )
        }
    }

    private fun buildRifleFromFields(): RifleProfile {
        val current = repo.getRifle()
        return current.copy(
            name = binding.etRifleName.text.toString().ifBlank { current.name },
            firearmTypeName = FirearmType.values()
                .getOrElse(binding.spFirearmType.selectedItemPosition) { current.firearmType }.name,
            caliberLabel = binding.etCaliber.text.toString().ifBlank { current.caliberLabel },
            barrelLengthIn = binding.etBarrel.dbl(current.barrelLengthIn),
            twistRateInPerTurn = binding.etTwist.dbl(current.twistRateInPerTurn),
            sightHeightIn = binding.etSightHeight.dbl(current.sightHeightIn),
            zeroDistanceM = binding.etZero.dbl(current.zeroDistanceM).coerceAtLeast(0.1)
        )
    }

    private fun buildBulletFromFields(): BulletProfile {
        val current = repo.getBullet()
        return current.copy(
            name = binding.etBulletName.text.toString().ifBlank { current.name },
            caliberDiameterIn = binding.etDiameter.dbl(current.caliberDiameterIn),
            weightGrains = binding.etWeight.dbl(current.weightGrains),
            muzzleVelocityFps = binding.etMv.dbl(current.muzzleVelocityFps),
            ballisticCoefficientG1 = binding.etBc.dbl(current.ballisticCoefficientG1)
        )
    }

    private fun buildScopeFromFields(): ScopeProfile {
        val current = repo.getScope()
        return current.copy(
            name = binding.etScopeName.text.toString().ifBlank { current.name },
            sightTypeName = SightType.values()
                .getOrElse(binding.spSightType.selectedItemPosition) { current.sightType }.name,
            clickUnit = ClickUnit.values()
                .getOrElse(binding.spClickUnit.selectedItemPosition) { current.clickUnit },
            clickMmAtReference = binding.etClickMm.dbl(current.clickMmAtReference),
            // Never allow zero: clickMrad divides by it.
            clickReferenceDistanceM = binding.etClickRef.dbl(current.clickReferenceDistanceM)
                .coerceAtLeast(0.1),
            sightRadiusMm = binding.etSightRadius.dbl(current.sightRadiusMm),
            maxElevationTravelMoa = binding.etElevTravel.dbl(current.maxElevationTravelMoa),
            maxWindageTravelMoa = binding.etWindTravel.dbl(current.maxWindageTravelMoa),
            invertElevationDirection = binding.cbInvertElev.isChecked,
            invertWindageDirection = binding.cbInvertWind.isChecked
        )
    }

    private fun saveActiveProfiles() {
        repo.saveRifle(buildRifleFromFields())
        repo.saveBullet(buildBulletFromFields())
        repo.saveScope(buildScopeFromFields())
        loadProfilesIntoFields()
        refreshSets()
        notifyUser("Saved. The active profile set is now shown as edited — use 'Save as…' to keep it.")
    }

    // ------------------------------------------------------------------
    //  Sets
    // ------------------------------------------------------------------

    private fun sets() = repo.getSets()

    private fun refreshSets() {
        val names = sets().map { it.name }
        binding.spSets.adapter = adapter(if (names.isEmpty()) listOf("(no saved sets)") else names)
        repo.getActiveSetName()?.let { active ->
            names.indexOf(active).takeIf { it >= 0 }?.let { binding.spSets.setSelection(it) }
        }
    }

    private fun applySelectedSet() {
        val set = sets().getOrNull(binding.spSets.selectedItemPosition) ?: return
        repo.applySet(set)
        loadProfilesIntoFields()
        notifyUser("Applied '${set.name}'.")
    }

    private fun saveCurrentAsSet() {
        val input = EditText(this).apply {
            hint = "Name for this set"
            setText(repo.getActiveSetName() ?: buildRifleFromFields().name)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Save the current firearm, load and sight as a set")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { notifyUser("A set needs a name."); return@setPositiveButton }
                repo.saveSet(ProfileSet(name, buildRifleFromFields(), buildBulletFromFields(), buildScopeFromFields()))
                repo.setActiveSetName(name)
                refreshSets()
                notifyUser("Saved '$name'.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSelectedSet() {
        val set = sets().getOrNull(binding.spSets.selectedItemPosition) ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete '${set.name}'?")
            .setPositiveButton("Delete") { _, _ -> repo.deleteSet(set.name); refreshSets() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------
    //  Catalogues
    // ------------------------------------------------------------------

    /**
     * The three catalogue pickers, with VTB's filters.
     *
     * A flat list of 41 firearms, 68 loads or 51 sights is unusable on a
     * phone — which is what these were before, a bare setItems() dialog. VTB
     * solved it with filter spinners above a results list and a live count,
     * and the same layouts are reused here so the two apps behave
     * identically: brand and type for a firearm; manufacturer, calibre,
     * velocity class, weight and bullet type for a load; brand, click value,
     * magnification class and family for a sight.
     */
    /** BLE discovery for a laser rangefinder (FIRE4000). The protocol is not
     *  published, so this enumerates the device and listens: range a target
     *  while it runs and the distance shows up in the Log as a value tracking
     *  the display, which identifies the characteristic and encoding. */
    /** Factory reset: every SharedPreferences store this app owns, so nothing
     *  survives that could contradict a fresh install. The API-key store is
     *  included — it is encrypted against a keystore key, and leaving it behind
     *  would strand ciphertext nobody can read. */
    private fun factoryReset() {
        val stores = listOf(
            "bas_prefs", "bas_units", "bas_theme", "bas_range", "bas_camera",
            "bas_distance", "bas_setup", "bas_import", "bas_environment",
            "sts_profiles", "sts_targets", "sts_rules", "sts_session", "vtb_environment"
        )
        for (name in stores) runCatching {
            getSharedPreferences(name, MODE_PRIVATE).edit().clear().apply()
        }
        runCatching { repo.resetToDefaults() }
        runCatching { repo.seedDefaultSetsIfEmpty() }
        runCatching { com.rfsat.bas.ui.SetupConfig.reset(this) }
    }

    /** Read the gun profiles the Kestrel itself holds, and offer to add any
     *  that BAS does not have. Read-only: nothing is written to the meter. */
    private fun importKestrelProfiles() {
        val missing = com.rfsat.bas.environment.RangefinderUi.missingPermissions(this)
        if (missing.isNotEmpty()) { requestPermissions(missing, 4308); return }
        val provider = com.rfsat.bas.environment.KestrelProvider
        fun go(d: android.bluetooth.BluetoothDevice) {
            com.rfsat.bas.environment.KestrelBallistics.read(this, d,
                status = { m -> runOnUiThread { notifyUser(m) } },
                onProfiles = { list ->
                    runOnUiThread {
                        if (list.isEmpty()) {
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("No profiles read")
                                .setMessage("The Kestrel's ballistics blocks came back empty. Open its " +
                                    "ballistics screen and select a gun, then try again — the Log holds " +
                                    "every block verbatim, which is what identifies the record format.")
                                .setPositiveButton("Open Log") { _, _ ->
                                    startActivity(Intent(this, LogActivity::class.java))
                                }
                                .setNegativeButton("Close", null).show()
                        } else {
                            val names = list.map { it.name }.toTypedArray()
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("Profiles on the Kestrel")
                                .setItems(names, null)
                                .setPositiveButton("Close", null).show()
                        }
                    }
                })
        }
        val bonded = provider.findPairedKestrel()
        if (bonded != null) go(bonded)
        else provider.scanForKestrel(this) { d ->
            if (d == null) notifyUser("No Kestrel found.") else go(d)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4309 && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            readEnvironment()
        }
    }

    private fun refreshEnvLabels() {
        binding.btnWeatherTier.text =
            "Source: ${com.rfsat.bas.environment.WeatherConfig.tier(this).label}"
        binding.btnWeatherDevice.text =
            "Default device: ${com.rfsat.bas.environment.EnvDeviceConfig.source(this).label}"
        binding.btnWeatherService.text =
            "Online service: ${com.rfsat.bas.environment.WeatherConfig.service(this).label}"
        binding.btnWeatherPosition.text =
            if (com.rfsat.bas.environment.WeatherConfig.hasPosition(this))
                "Position: %.3f, %.3f".format(
                    com.rfsat.bas.environment.WeatherConfig.latitude(this),
                    com.rfsat.bas.environment.WeatherConfig.longitude(this))
            else "Position: from the phone"
        binding.tvEnvSummary.text = runCatching {
            com.rfsat.bas.environment.EnvironmentManager.describe()
        }.getOrDefault("")
    }

    /** Read conditions from the chosen source. The phone is always available;
     *  a meter needs Bluetooth permission and a scan. */
    private fun readEnvironment() {
        val tier = com.rfsat.bas.environment.WeatherConfig.tier(this)
        if (tier != com.rfsat.bas.environment.WeatherTier.METER) {
            // An online forecast needs a position. The permission was declared
            // but never ASKED for, which is why a fetch simply stalled — so ask
            // now, and if it is refused offer to type the coordinates instead.
            val needsFix = (tier == com.rfsat.bas.environment.WeatherTier.ONLINE ||
                tier == com.rfsat.bas.environment.WeatherTier.AUTO) &&
                !com.rfsat.bas.environment.WeatherConfig.hasPosition(this)
            val granted = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (needsFix && !granted) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Where are you shooting?")
                    .setMessage("An online forecast is for a place, so BAS needs either permission " +
                        "to read the phone's location or the coordinates typed in. Nothing is sent " +
                        "anywhere except the weather request itself.")
                    .setPositiveButton("Allow location") { _, _ ->
                        requestPermissions(arrayOf(
                            android.Manifest.permission.ACCESS_COARSE_LOCATION), 4309)
                    }
                    .setNeutralButton("Type coordinates") { _, _ -> binding.btnWeatherPosition.performClick() }
                    .setNegativeButton("Cancel", null)
                    .show()
                return
            }
            notifyUser("Fetching conditions…")
            com.rfsat.bas.environment.WeatherSource.refresh(this) { _, msg ->
                runOnUiThread { refreshEnvLabels(); notifyUser(msg) }
            }
            return
        }
        val src = com.rfsat.bas.environment.EnvDeviceConfig.source(this)
        if (src == com.rfsat.bas.environment.EnvSource.PHONE) {
            com.rfsat.bas.environment.EnvironmentManager.refreshFromPhoneSensors(this) {
                runOnUiThread { refreshEnvLabels(); notifyUser("Read from the phone's sensors.") }
            }
            return
        }
        val missing = com.rfsat.bas.environment.RangefinderUi.missingPermissions(this)
        if (missing.isNotEmpty()) { requestPermissions(missing, 4307); return }
        notifyUser("Looking for the meter…")
        val provider = com.rfsat.bas.environment.KestrelProvider
        fun read(d: android.bluetooth.BluetoothDevice) {
            provider.read(this, d) { ok ->
                runOnUiThread {
                    refreshEnvLabels()
                    notifyUser(if (ok) "Conditions updated." else "No values returned — see the Log tab.")
                }
            }
        }
        val bonded = provider.findPairedKestrel()
        if (bonded != null) { read(bonded); return }
        provider.scanForKestrel(this) { d ->
            if (d == null) notifyUser("No meter found — check it is switched on and nearby.")
            else read(d)
        }
    }

    private fun refreshRangefinderLabel() {
        binding.btnRangefinderModel.text =
            "Rangefinder: ${com.rfsat.bas.environment.DistanceConfig.model(this).label}"
    }

    /** Connect to the configured rangefinder and show the first range it
     *  reports — the quickest way to tell whether the link works at all. */
    private fun testRangefinder() {
        val model = com.rfsat.bas.environment.DistanceConfig.model(this)
        if (model == com.rfsat.bas.environment.RangefinderModel.MANUAL) {
            notifyUser("Set to enter distance by hand — choose a rangefinder first."); return
        }
        val missing = com.rfsat.bas.environment.RangefinderUi.missingPermissions(this)
        if (missing.isNotEmpty()) { requestPermissions(missing, 4306); return }
        com.rfsat.bas.environment.RangefinderUi.readDistance(this, model,
            status = { msg -> runCatching { notifyUser(msg) } },
            onMetres = { m ->
                runCatching {
                    notifyUser("Range: ${String.format("%.0f", m)} m — link works.")
                    com.rfsat.bas.ui.Speaker.say(this, "Range ${String.format("%.0f", m)} metres.")
                }
            })
    }

    private fun rangefinderProbe() {
        val needed = if (android.os.Build.VERSION.SDK_INT >= 31) arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
        ) else arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = needed.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) { requestPermissions(missing.toTypedArray(), 4301); return }

        val probe = com.rfsat.bas.environment.RangefinderProbe
        fun run(device: android.bluetooth.BluetoothDevice) {
            notifyUser("Rangefinder: connecting — then range a target while it listens.")
            probe.probe(this, device) { msg -> runCatching { notifyUser(msg) } }
        }
        val bonded = probe.findPaired()
        if (bonded != null) { run(bonded); return }
        notifyUser("Scanning for a rangefinder…")
        probe.scan(this) { device ->
            if (device == null)
                notifyUser("No rangefinder found — make sure it is on and discoverable. Advertisers seen were logged (Log tab).")
            else run(device)
        }
    }

    private fun rangeOptionsDialog() {
        // Built from real CheckBoxes rather than setMultiChoiceItems so the
        // label font can be reduced — at the default size these wrap to two
        // lines and the list is hard to scan.
        val labels = arrayOf(
            "Speak corrections and scores",
            "Keep screen on during a session",
            "Auto-reconnect camera Wi-Fi",
            "Auto-advance to results after a shot",
            "Auto-collect new clips from camera",
            "Volume / Bluetooth remote triggers",
            "Skip confirmations")
        val initial = booleanArrayOf(
            com.rfsat.bas.ui.RangeSettings.speak(),
            com.rfsat.bas.ui.RangeSettings.keepAwake(),
            com.rfsat.bas.ui.RangeSettings.autoReconnect(),
            com.rfsat.bas.ui.RangeSettings.autoShowResults(),
            com.rfsat.bas.ui.RangeSettings.autoCollect(),
            com.rfsat.bas.ui.RangeSettings.remoteTrigger(),
            com.rfsat.bas.ui.RangeSettings.skipConfirm())
        val pad = (12 * resources.displayMetrics.density).toInt()
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad + pad / 2, pad, pad, pad)
        }
        val boxes = labels.mapIndexed { i, text ->
            android.widget.CheckBox(this).apply {
                this.text = text
                isChecked = initial[i]
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                column.addView(this)
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Range options")
            .setView(android.widget.ScrollView(this).apply { addView(column) })
            .setPositiveButton("Save") { _, _ ->
                com.rfsat.bas.ui.RangeSettings.setSpeak(this, boxes[0].isChecked)
                com.rfsat.bas.ui.RangeSettings.setKeepAwake(this, boxes[1].isChecked)
                com.rfsat.bas.ui.RangeSettings.setAutoReconnect(this, boxes[2].isChecked)
                com.rfsat.bas.ui.RangeSettings.setAutoShowResults(this, boxes[3].isChecked)
                com.rfsat.bas.ui.RangeSettings.setAutoCollect(this, boxes[4].isChecked)
                com.rfsat.bas.ui.RangeSettings.setRemoteTrigger(this, boxes[5].isChecked)
                com.rfsat.bas.ui.RangeSettings.setSkipConfirm(this, boxes[6].isChecked)
                if (boxes[0].isChecked) com.rfsat.bas.ui.Speaker.init(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun cameraDefaultsMenu() {
        val items = arrayOf("Default camera type", "TACTACAM address", "ShotKam address", "RTSP / MJPEG address")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Camera defaults")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> com.rfsat.bas.capture.CameraUi.chooseType(this, com.rfsat.bas.capture.CameraConfig.type(this)) {
                        com.rfsat.bas.capture.CameraConfig.setType(this, it); notifyUser("Default camera: ${it.label}")
                    }
                    1 -> hostDefault(com.rfsat.bas.capture.CameraType.TACTACAM)
                    2 -> hostDefault(com.rfsat.bas.capture.CameraType.SHOTKAM)
                    3 -> hostDefault(com.rfsat.bas.capture.CameraType.RTSP)
                }
            }
            .show()
    }

    private fun hostDefault(type: com.rfsat.bas.capture.CameraType) {
        com.rfsat.bas.capture.CameraUi.promptHost(this, type, com.rfsat.bas.capture.CameraConfig.host(this, type)) {
            com.rfsat.bas.capture.CameraConfig.setHost(this, type, it)
        }
    }

    private fun showRifleCatalog() {
        val view = layoutInflater.inflate(R.layout.dialog_rifle_catalog, null)
        val spBrand = view.findViewById<android.widget.Spinner>(R.id.spRifBrand)
        val spType = view.findViewById<android.widget.Spinner>(R.id.spRifType)
        val tvCount = view.findViewById<android.widget.TextView>(R.id.tvRifCount)
        val list = view.findViewById<android.widget.ListView>(R.id.lvRifResults)

        spBrand.adapter = adapter(RifleCatalog.brands())
        spType.adapter = adapter(RifleCatalog.types())

        var shown: List<RifleCatalog.Entry> = emptyList()
        fun refilter() {
            shown = RifleCatalog.filter(
                spBrand.selectedItem?.toString() ?: RifleCatalog.ALL,
                spType.selectedItem?.toString() ?: RifleCatalog.ALL
            )
            list.adapter = WrappingNameAdapter(this, shown.map { it.label() })
            tvCount.text = "${shown.size} of ${RifleCatalog.all.size} firearms"
        }
        val onFilter = onSelected { refilter() }
        spBrand.onItemSelectedListener = onFilter
        spType.onItemSelectedListener = onFilter
        refilter()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Firearm catalogue")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()
        list.setOnItemClickListener { _, _, i, _ ->
            shown.getOrNull(i)?.let { e ->
                repo.saveRifle(e.toRifleProfile())
                loadProfilesIntoFields()
                notifyUser("Loaded ${e.brand} ${e.model}. Adjust the fields for your own rifle.")
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showAmmoCatalog() {
        val view = layoutInflater.inflate(R.layout.dialog_ammo_catalog, null)
        val spMfr = view.findViewById<android.widget.Spinner>(R.id.spCatMfr)
        val spCal = view.findViewById<android.widget.Spinner>(R.id.spCatCal)
        val spVel = view.findViewById<android.widget.Spinner>(R.id.spCatVel)
        val spWeight = view.findViewById<android.widget.Spinner>(R.id.spCatWeight)
        val spType = view.findViewById<android.widget.Spinner>(R.id.spCatType)
        val tvCount = view.findViewById<android.widget.TextView>(R.id.tvCatCount)
        val list = view.findViewById<android.widget.ListView>(R.id.lvCatResults)

        spMfr.adapter = adapter(AmmoCatalog.manufacturers())
        spCal.adapter = adapter(AmmoCatalog.calibers())
        spVel.adapter = adapter(AmmoCatalog.velocityClasses())
        spWeight.adapter = adapter(AmmoCatalog.weights())
        spType.adapter = adapter(AmmoCatalog.types())

        var shown: List<AmmoCatalog.Entry> = emptyList()
        fun refilter() {
            shown = AmmoCatalog.filter(
                spMfr.selectedItem?.toString() ?: AmmoCatalog.ALL,
                spCal.selectedItem?.toString() ?: AmmoCatalog.ALL,
                spVel.selectedItem?.toString() ?: AmmoCatalog.ALL,
                spWeight.selectedItem?.toString() ?: AmmoCatalog.ALL,
                spType.selectedItem?.toString() ?: AmmoCatalog.ALL
            )
            list.adapter = WrappingNameAdapter(this, shown.map { it.label() })
            tvCount.text = "${shown.size} of ${AmmoCatalog.all.size} loads"
        }
        val onFilter = onSelected { refilter() }
        listOf(spMfr, spCal, spVel, spWeight, spType).forEach { it.onItemSelectedListener = onFilter }
        refilter()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Ammunition catalogue")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()
        list.setOnItemClickListener { _, _, i, _ ->
            shown.getOrNull(i)?.let { e ->
                repo.saveBullet(e.toBulletProfile())
                loadProfilesIntoFields()
                notifyUser("Loaded ${e.manufacturer} ${e.product}. Published figures — refine them " +
                    "against your own chronograph.")
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showScopeCatalog() {
        val view = layoutInflater.inflate(R.layout.dialog_scope_catalog, null)
        val spBrand = view.findViewById<android.widget.Spinner>(R.id.spScBrand)
        val spClick = view.findViewById<android.widget.Spinner>(R.id.spScClick)
        val spMag = view.findViewById<android.widget.Spinner>(R.id.spScMag)
        val spFamily = view.findViewById<android.widget.Spinner>(R.id.spScFamily)
        val tvCount = view.findViewById<android.widget.TextView>(R.id.tvScCount)
        val list = view.findViewById<android.widget.ListView>(R.id.lvScResults)

        spBrand.adapter = adapter(ScopeCatalog.brands())
        spClick.adapter = adapter(ScopeCatalog.clickUnits())
        spMag.adapter = adapter(ScopeCatalog.magClasses())
        spFamily.adapter = adapter(ScopeCatalog.families())

        var shown: List<ScopeCatalog.Entry> = emptyList()
        fun refilter() {
            shown = ScopeCatalog.filter(
                spBrand.selectedItem?.toString() ?: ScopeCatalog.ALL,
                spClick.selectedItem?.toString() ?: ScopeCatalog.ALL,
                spMag.selectedItem?.toString() ?: ScopeCatalog.ALL,
                spFamily.selectedItem?.toString() ?: ScopeCatalog.ALL
            )
            list.adapter = WrappingNameAdapter(this, shown.map { it.label() })
            tvCount.text = "${shown.size} of ${ScopeCatalog.all.size} sights"
        }
        val onFilter = onSelected { refilter() }
        listOf(spBrand, spClick, spMag, spFamily).forEach { it.onItemSelectedListener = onFilter }
        refilter()

        val dialog = AlertDialog.Builder(this)
            .setTitle("Sight catalogue")
            .setView(view)
            .setNegativeButton("Cancel", null)
            .create()
        list.setOnItemClickListener { _, _, i, _ ->
            shown.getOrNull(i)?.let { e ->
                repo.saveScope(e.toScopeProfile())
                loadProfilesIntoFields()
                notifyUser("Loaded ${e.brand} ${e.model}.")
            }
            dialog.dismiss()
        }
        dialog.show()
    }

    // ------------------------------------------------------------------
    //  Backup
    // ------------------------------------------------------------------

    private fun exportBackup() {
        val json = AppBackup.export(this)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "BAS backup")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(send, "Export the BAS backup"))
    }

    private fun importBackup() {
        val input = EditText(this).apply {
            hint = "Paste the backup JSON here"
            setLines(6)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        AlertDialog.Builder(this)
            .setTitle("Restore from a backup")
            .setMessage("This replaces your profile sets, custom targets and custom rules with the " +
                "contents of the backup. Sessions already recorded are not affected.")
            .setView(input)
            .setPositiveButton("Restore") { _, _ ->
                val result = AppBackup.import(this, input.text.toString())
                notifyUser(result)
                loadProfilesIntoFields()
                refreshSets()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------------

    private fun adapter(items: List<String>) =
        ArrayAdapter(this, R.layout.spinner_item, items).also {
            it.setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

    /** Filter spinners only ever need "something changed". */

    /**
     * Puts the long explanation behind a link instead of under the switch.
     *
     * The settings screen had grown a paragraph per option — mechanism,
     * measured evidence, the reasoning behind a default. All true, none of it
     * what someone deciding whether to tick a box needs to read. The line
     * under each option now says what to expect; the paragraph is one tap
     * away for anyone who wants to know why.
     */
    private fun moreInfo(view: android.widget.TextView, title: String, body: String) {
        view.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun wireMoreInfo() {
        moreInfo(binding.infoScale, "Scale",
            "Millimetres per pixel can be measured from the spacing of the printed rings, or " +
            "from the radius of the black aiming mark against the ratio the catalogue gives for " +
            "this face. The two are independent. Cross-checking averages them when they agree " +
            "and reports it when they do not, which usually means the wrong face is selected.")
        moreInfo(binding.infoWedge, "Tilt-axis ring spacing",
            "Along the axis a card tilts about, the distance to the camera does not change, so " +
            "the scale there is exact. Measuring only in that direction should remove the drift " +
            "seen on angled cards. On the images tested it helped on some and hurt on others, " +
            "so it needs real range photographs before it can be trusted.")
        moreInfo(binding.infoSource, "Find shots in the photograph",
            "Looks for holes in the picture as it arrived rather than in the flattened copy, and " +
            "reads plain brightness inside the black aiming mark — where the colour comparison " +
            "used elsewhere has nothing left to measure, because black ink and the grey fibres " +
            "of a hole through it are equally unlike the paper. On the test card this is the " +
            "difference between a score of 10 and the correct 19.")
        moreInfo(binding.infoPuncture, "Puncture test",
            "A hole takes the most material out of its centre, so it gets steadily lighter " +
            "outwards until it reaches the paper. A printed roundel or a letter does not. On " +
            "the test card this removed a piece of the maker's footer that was being reported " +
            "as a shot, and kept every real hole.")
        moreInfo(binding.infoOutside, "Shots that missed",
            "Shots outside the outermost ring score nothing, so this cannot change a total. It " +
            "is for seeing where a flyer went: a plot that quietly omits the worst shots of a " +
            "string misrepresents the group. Everything out there is print, so this needs the " +
            "puncture test and applies it more strictly — and on the test card it still marks " +
            "some of the footer and the logos.")
        moreInfo(binding.infoFamily, "Scale from fitted ring circles",
            "Fits a circle to every visible ring instead of reading their radii off one averaged " +
            "radial profile. On a flat scan both recover the true ring pitch to a thousandth of " +
            "a millimetre. It is here because it reports how far each ring individually sits " +
            "from where the catalogue puts it, which says whether a card is flat.")
        moreInfo(binding.infoCloud, "Second opinion",
            "The service you choose looks at the photograph and reports how many holes it can see and roughly " +
            "where. It does not score: a position read off a picture carries several " +
            "millimetres, while the app measures a hole it has found to under two. Anything it " +
            "sees that the app did not is offered as a suggestion, and the app measures the " +
            "position before any shot is placed.")
        moreInfo(binding.infoOverride, "The AI answer overrides the app",
            "Marks the service does not see are removed and shots it sees that the app missed " +
            "are added, without asking. It uses the AI positions, which carry several millimetres " +
            "against the 0.2 to 1.7 mm the app measures for a hole it can see — on a 10 m face " +
            "the rings are 8 mm apart, so a shot placed this way can be a ring out. Added shots " +
            "are marked hand-placed for that reason.")
        moreInfo(binding.infoEngine, "What scores a card",
            "Embedded runs the app's own detection, with a service available afterwards as a " +
            "second opinion. Naming a service instead skips the app's detection entirely — that " +
            "service finds and scores the shots, and the app only works out where the card is, " +
            "because without that nothing can be drawn in the right place. The picture sent is " +
            "the flattened card, so the marks land exactly where you see them. This choice and " +
            "the second opinion's are separate: they can name different services, and asking " +
            "the other one is what makes a second opinion worth having. Falls back to Embedded " +
            "if the named service has no key.")
        moreInfo(binding.infoCamera, "Telling the app how the camera is set",
            "The app cannot change anything on a Wi-Fi camera: none of these cameras publishes " +
            "how they are controlled, and an app that sent commands it had guessed at would be " +
            "worse than one that sent none. What it can do is know what you set, and say what " +
            "that will cost.\n\n" +
            "The red dot is the sharpest example. It is drawn into the video at the centre of " +
            "the frame — which, once the card is lined up, is where the ten ring is. It is " +
            "small, round and unlike the paper, so it reads as a hole, in the one place a " +
            "shooter is least likely to question one. With it declared on, marks within half a " +
            "gauge of the frame centre are ignored.\n\n" +
            "Stabilisation moves the picture between frames to cancel shake, and live detection " +
            "reads what moved as a shot. Auto white balance and auto exposure drift, and the " +
            "detector works from how far each pixel sits from the paper's own colour. Zoom " +
            "changes the focal length and with it the barrel distortion. The video size lets " +
            "the app say whether the stream matches what you set — usually it does not, because " +
            "that setting governs what goes to the card.")
        moreInfo(binding.infoReticle, "The reticle",
            "It is drawn over the picture and it changes no score: the app has no idea where " +
            "the barrel points, and a shot is scored from the hole in the paper. It is there to " +
            "line the camera up, and to stay out of the way.\n\n" +
            "Choose None when the camera looks through a scope. That camera already shows the " +
            "scope's own reticle, and a second one drawn a few pixels away is worse than " +
            "neither — it is the app arguing with the optic.\n\n" +
            "The built-in reticles are drawn as line work, so they take the theme's colour and " +
            "stay red under the night-red theme. An image of your own is drawn exactly as it " +
            "comes, which is the point of it, so a transparent PNG works best and it will not " +
            "follow the theme.\n\n" +
            "Separate from the ring guide on the Session tab, which draws the SELECTED FACE'S " +
            "rings and does say something: whether the card in front of the camera is the one " +
            "you chose. Switching the reticle off does not switch that check off.")
        moreInfo(binding.infoStreamLens, "Lens correction on a live stream",
            "A short-focus camera bows straight lines outward, most at the edges of the frame " +
            "and not at all in the middle, so a ring near the edge measures short. Down a range " +
            "it is negligible; filling the frame from close to a card it is not.\n\n" +
            "The figure is measured on Import from a photograph taken with the same camera, " +
            "where the app can compare the fitted rings against the even spacing they are " +
            "printed at. It is entered here rather than measured live because an estimate that " +
            "wanders from frame to frame would change the scoring geometry underneath a string " +
            "that is being shot.\n\n" +
            "Negative is barrel, which is what a wide lens gives. Zero, or an empty box, is off.")
        moreInfo(binding.infoKey, "API key",
            "The key comes from that service's own console — console.anthropic.com for Claude, " +
            "platform.openai.com for OpenAI — and bills that account. It is not the " +
            "password you sign in to the chat service with; those will not work here. A key is " +
            "kept for each service separately and encrypted on this device, and is never " +
            "written to the log. A card costs a fraction of a penny to check, and needs a " +
            "connection, which most ranges do not have.")
    }

    /**
     * The Wi-Fi camera's own settings, as described by the shooter.
     *
     * Every control writes the WHOLE profile back rather than one field,
     * because the advice underneath depends on the combination — and reading
     * six controls to build it is cheaper than keeping six of them in step.
     */
    private fun wireCameraProfile() {
        val evs = listOf(-2.0, -1.0, 0.0, 1.0, 2.0)
        fun current() = ScaleSettings.cameraProfile()
        fun save(p: com.rfsat.bas.detect.CameraProfile) {
            ScaleSettings.setCameraProfile(this, p)
            refreshCameraAdvice()
        }

        binding.spCamZoom.adapter = adapter(com.rfsat.bas.detect.CameraZoom.values().map { it.label })
        binding.spCamZoom.setSelection(
            com.rfsat.bas.detect.CameraZoom.values().indexOf(current().zoom))
        binding.spCamZoom.onItemSelectedListener = onSelectedIndex { i ->
            com.rfsat.bas.detect.CameraZoom.values().getOrNull(i)
                ?.let { save(current().copy(zoom = it)) }
        }

        binding.spCamVideo.adapter =
            adapter(com.rfsat.bas.detect.CameraVideoSize.values().map { it.label })
        binding.spCamVideo.setSelection(
            com.rfsat.bas.detect.CameraVideoSize.values().indexOf(current().videoSize))
        binding.spCamVideo.onItemSelectedListener = onSelectedIndex { i ->
            com.rfsat.bas.detect.CameraVideoSize.values().getOrNull(i)
                ?.let { save(current().copy(videoSize = it)) }
        }

        binding.spCamWb.adapter =
            adapter(com.rfsat.bas.detect.CameraWhiteBalance.values().map { it.label })
        binding.spCamWb.setSelection(
            com.rfsat.bas.detect.CameraWhiteBalance.values().indexOf(current().whiteBalance))
        binding.spCamWb.onItemSelectedListener = onSelectedIndex { i ->
            com.rfsat.bas.detect.CameraWhiteBalance.values().getOrNull(i)
                ?.let { save(current().copy(whiteBalance = it)) }
        }

        binding.spCamEv.adapter = adapter(evs.map { "%+.1f".format(it) })
        binding.spCamEv.setSelection(
            evs.indexOfFirst { kotlin.math.abs(it - current().exposureCompensationEv) < 0.01 }
                .takeIf { it >= 0 } ?: 2)
        binding.spCamEv.onItemSelectedListener = onSelectedIndex { i ->
            evs.getOrNull(i)?.let { save(current().copy(exposureCompensationEv = it)) }
        }

        binding.spCamMains.adapter = adapter(com.rfsat.bas.detect.CameraMains.values().map { it.label })
        binding.spCamMains.setSelection(
            com.rfsat.bas.detect.CameraMains.values().indexOf(current().mains))
        binding.spCamMains.onItemSelectedListener = onSelectedIndex { i ->
            com.rfsat.bas.detect.CameraMains.values().getOrNull(i)
                ?.let { save(current().copy(mains = it)) }
        }

        binding.cbCamRedDot.isChecked = current().redDot
        binding.cbCamRedDot.setOnClickListener {
            save(current().copy(redDot = binding.cbCamRedDot.isChecked))
        }
        binding.cbCamStab.isChecked = current().stabilisation
        binding.cbCamStab.setOnClickListener {
            save(current().copy(stabilisation = binding.cbCamStab.isChecked))
        }
        refreshCameraAdvice()
    }

    /** What the described setup will do to a score, said here rather than
     *  discovered on the plot afterwards. */
    private fun refreshCameraAdvice() {
        val p = ScaleSettings.cameraProfile()
        val lines = p.advice() + listOf(p.distortionExpectation())
        binding.tvCameraAdvice.text =
            com.rfsat.bas.ui.Bullets.list(lines, binding.tvCameraAdvice.textSize)
    }

    /** The reticle line, and the button that changes it. */
    private fun refreshReticle() {
        val r = ScaleSettings.reticle()
        binding.spReticle.setSelection(com.rfsat.bas.ui.Reticle.values().indexOf(r))
        binding.tvReticle.text = when {
            r == com.rfsat.bas.ui.Reticle.CUSTOM && ScaleSettings.reticleFile().isNotEmpty() ->
                "Using your own image. Choose “My own image…” again to replace it."
            r == com.rfsat.bas.ui.Reticle.CUSTOM -> "No image loaded yet."
            r == com.rfsat.bas.ui.Reticle.NONE -> "Nothing is drawn over the picture."
            else -> "Drawn in the theme colour, at the guide size set on the Session tab."
        }
    }

    private fun onSelected(block: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = block()
        override fun onNothingSelected(p: AdapterView<*>?) = Unit
    }

    private fun onSelectedIndex(block: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) = block(position)
        override fun onNothingSelected(p: AdapterView<*>?) = Unit
    }

    private fun EditText.dbl(fallback: Double): Double =
        text.toString().trim().replace(',', '.').toDoubleOrNull() ?: fallback

    private fun num(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e9) "%.0f".format(v)
        else "%.4f".format(v).trimEnd('0').trimEnd('.')

    override fun swipeExemptViews(): List<View> = listOf(
        binding.spTheme, binding.spUnits, binding.spSets,
        binding.spFirearmType, binding.spSightType, binding.spClickUnit
    )
}
