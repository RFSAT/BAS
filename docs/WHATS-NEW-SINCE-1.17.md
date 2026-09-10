# BAS — what changed since 1.17.0

**SUPERSEDED.** Both documents have since been rebuilt — the User Guide at
1.41.3 and the Programmer Reference at 1.41.4 — and both are better copies of
what is below. This file is kept only as the record of what changed between
1.17.0 and those rebuilds; read the guides.

Originally, both documents in this folder described **1.17.0**.
The app is now considerably further on. This file records everything a reader
of those documents would find missing, so that neither document has to be
trusted where it is silent — and so that whoever next rebuilds them has the
material in one place rather than in sixty changelog entries.

Written for two audiences in turn. Nothing here repeats what the guides
already cover correctly.

---

## Part one — for the shooter

### The firing solution grew three terms

The ballistics chapter of the guide describes drag and gravity. Three further
effects are now modelled. All three are *systematic*: they move the whole
group rather than widening it, so shooting more does not average them out.

**Spin drift.** A stable bullet slides toward the direction of barrel twist —
about 22 cm at 1000 m for a 175 gr .308. The twist rate has always been part
of the rifle profile; until 1.27.0 nothing read it.

**Coriolis.** Horizontal deflection depends only on latitude (about 8 cm at
1000 m at European latitudes, to the right in the northern hemisphere). The
vertical part depends on which way the rifle points: **east shoots high, west
shoots low**, north and south cancel. Position comes from the weather
settings; the bearing is read from the phone's compass while it is aimed at
the target, and expires after five minutes so a pocketed phone cannot supply
a stale heading.

**Cant.** Tilting the rifle swings dialled elevation out of the vertical: 5°
at 600 m with 3 mrad of come-up throws about 16 cm sideways. It scales with
the elevation *held*, not with distance, which is why it bites at long range
and on air rifles alike. Set it under Settings → Rifle orientation, either by
declaring the phone rail-mounted or by entering the angle.

Under about 300 m all three are smaller than a centimetre. Where an input is
missing the term is left out rather than guessed, and past 600 m the Results
screen says which one is missing.

### Muzzle velocity is no longer taken from the box

Catalogue velocities are measured in a test barrel. The app now corrects for
the barrel actually fitted — roughly 25 fps per inch on a centrefire, which
is 100 fps for a 20 in barrel against a 24 in test barrel. **Rimfire reverses
above about 16 in**, and is handled separately.

Powder temperature also reaches the solution now. It is applied to the shot
and not to the zero: the zero was established in the past at the load's
reference temperature, and re-solving it would cancel most of the effect.

### Truing — fitting the trajectory to your own dope

Settings → Ballistics results → **Truing** records a scored group at a known
distance and fits the profile to it. Velocity is fitted from groups inside
500 m and drag from groups beyond it, never both at one distance — they are
separable only because they act with different shapes against range, and
fitting both at one range returns a large velocity error cancelled by an
absurd BC.

The result is an **overlay** against that rifle and that load. The ammunition
catalogue is never modified, both figures are shown together, and the overlay
can be discarded. A fit outside what a real barrel or bullet does is reported
as a probable distance or zero error rather than accepted.

### A second opinion, from any of six services

Claude, OpenAI, xAI (Grok), Mistral and Google Gemini are supported directly;
**OpenRouter** is offered last because it is a router rather than a service —
its entries read "(via OpenRouter)" and reach other vendors' models on an
OpenRouter key.

- Keys are stored encrypted, one per service.
- "Use only free models" is a control for OpenRouter alone, where free access
  is a different set of models. Gemini and Mistral have free tiers that belong
  to the key and apply automatically — the box is greyed and says so. The
  others are paid only.
- Free models are rate-limited and many cannot answer against a fixed schema,
  so a free model may fail where a paid one succeeds. That is expected.
- **DeepSeek is not offered.** Its published API takes text only and every
  task here sends a photograph.

Every comparison is filed. Settings → **Detection accuracy record** shows how
often the app and the service agreed, and exports the record.

### Scoring and the firing point

- **Undo** on every deletion, from the notification that reports it.
- **Range mode** shows an instrument chip where a link that is *connected but
  silent* reads differently from a working one.
- **Mirrored controls** put the first button of each row under the free hand.
- **String labels** from a list that learns whatever is typed.
- **Settings filter** that searches the interface in whatever language it is
  displaying.
- The **last shot** on the plot is marked by a wider ring as well as by
  colour, and the colour pair no longer collapses under red-green colour
  blindness or the night-red theme.

### Languages

The interface translates into every EU language, cached so it works without a
signal afterwards. English always restores the original embedded text rather
than a translation back from another language.

### Data safety

- After a crash the app does not reload the stored session — and no longer
  overwrites it either. It is set aside, and Settings can recover it.
- Every new build **snapshots everything before it touches storage**, keeping
  the last five. Settings → Snapshots taken before an upgrade.
- **Backups now carry every setting**, and optionally the API keys. Keys are
  asked for each time and written in plain text when included, because a
  backup cannot carry the encryption they normally sit behind. A backup can be
  saved to the phone, shared or copied; a restore accepts a file or pasted
  text.

---

## Part two — for the programmer

### New in `ballistics`

| Type | Purpose |
|---|---|
| `DriftCorrections` | Miller gyroscopic stability, Litz spin drift, first-order Coriolis (horizontal and Eötvös), cant. Returns metres at the target in the trajectory's own frame. |
| `MuzzleVelocity` | Test-barrel to fitted-barrel correction; separate rimfire rule with a peak near 16 in. |
| `Truing` | Golden-section fit of muzzle velocity (near) and `dragCalibrationFactor` (far) against recorded drops. Not Newton: the trajectory is integrated numerically and its derivative is only available as a difference of two noisy simulations. |

`AdjustmentCalculator.computeAdjustment` gained a defaulted `geometry:
ShotGeometry` parameter carrying latitude, firing azimuth and cant, each
nullable so a missing input omits its term instead of asserting zero.

### New elsewhere

| Type | Purpose |
|---|---|
| `environment/ShotOrientation` | Compass bearing and roll from the rotation-vector sensor, with a five-minute freshness limit. |
| `environment/LinkStatus` | Instrument state, including STALE — connected but silent, which the Bluetooth stack reports as connected indefinitely. |
| `profiles/TruingStore` | Observations and the fitted overlay, keyed by rifle *and* load. Sets `testBarrelIn` to the fitted barrel so the barrel correction is not applied twice. |
| `backup/UpgradeSnapshot` | Pre-upgrade copy, taken synchronously on the main thread on purpose — a snapshot that might land after the damage is not a snapshot. |
| `detect/DetectionAudit` | Files each reconciliation with the detector's own per-hole numbers. The training set for the planned classifier work. |
| `ui/StringLabels` | Learned label list. |
| `cloud/AiProvider` | Provider table: `readsImages`, `selectable`, `tokenLimitField`, `freeAccess`. Declaration order is picker order. |

### Behaviours worth knowing before changing anything

- **Persistence refuses to write until it has read.** `ScoringSession`,
  `AnalysisSession` and `EnvironmentManager` each hold a `loaded` flag. Safe
  mode skips their restore, which leaves them empty, and an empty store
  written back is data loss. Sessions also copy the payload they decline to
  open into a rescue slot.
- **R8 is no longer blanket-keeping the app.** `proguard-rules.pro` keeps
  field names only for the eight packages Gson persists, plus enum constants
  and View constructors. A gate fails the build if a type is persisted from a
  package with no rule — the symptom otherwise is silent data loss in release
  builds only.
- **"OpenAI-compatible" is compatible in shape, not in field names.** OpenAI
  renamed `max_tokens` to `max_completion_tokens`; nobody else followed.
- **Gemini is not OpenAI-shaped.** Model in the URL, key in an
  `x-goog-api-key` header, and a schema dialect that rejects the
  `additionalProperties` OpenAI's strict mode requires.

### Toolchain

AGP 9.0.0 on Gradle 9.7.0, JDK 17, compileSdk 36. **AGP 9 has built-in
Kotlin**: the `org.jetbrains.kotlin.android` plugin must not be applied, and
the Kotlin version is whatever AGP embeds. `kotlinOptions {}` is gone —
`kotlin { compilerOptions { } }` replaces it. AGP 9 creates
`testDebugUnitTest` only; there is no release unit-test task.

### The static checks

`tools/kotlin_checks.py` runs before compilation in CI. Gates cover exhaustive
`when`, view-binding ids against layouts, import ordering, cross-package
unqualified references, `this` inside coroutine builders, layout dimensions
against style chains, Gson types against ProGuard rules, and AGP/Kotlin plugin
conflicts.

They check **patterns, not types**. Gate 15 was written to catch local
functions called before declaration and **withdrawn**: indentation cannot
distinguish a local function from a method of a nested anonymous object, so it
reported valid code while still missing the case it was written for. Wrong in
both directions is worse than absent.
