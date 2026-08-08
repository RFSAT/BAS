# BAS — Ballistics and Scoring: integration notes

BAS is a new, single application that merges **VTB** (Vapor-Trail Ballistics — put
shots on centre) and **STS** (Shooting Target Scorer — grade the group) into one
product. STS is the base; VTB's ballistics stack was folded into it.

- `applicationId` = `com.BAS` (new Play listing, permanent, starts at `versionCode 1`)
- Kotlin/resource namespace = `com.rfsat.bas`
- `versionName` = `1.0.0`, `versionCode` = `1`
- Build: AGP 8.9.1, Kotlin 2.1.0, Gradle 8.11.1, compileSdk 36, minSdk 26

## Navigation — the usability spine

Five bottom-nav tabs, left->right as the shooter works, within Material's 5-item cap:

| Tab | Opens | Origin |
|-----|-------|--------|
| Home | `ui.MainActivity` | STS |
| Ballistics | `capture.CaptureActivity` | VTB |
| Score | `detect.SessionActivity` | STS |
| Results | `results.ResultsActivity` (scoring) | STS |
| Settings | `profiles.ProfileActivity` | STS |

Targets, Rules, Log, and Backup are reached from Settings (a **Target faces...**
button was added to Settings, mirroring STS's existing Rules/Log buttons). The
ballistics adjustment screen (`results.BallisticsResultsActivity`) is reached from
the Ballistics flow and highlights the Ballistics tab. Routing and swipe-order
live in `ui.BaseActivity` (`openTab` + `tabOrder`).

## What was merged, and why it was clean

The STS codebase was already written to be cross-compatible with VTB, which made
the merge far cleaner than two arbitrary apps:

- **Profiles** — STS's `RifleProfile`, `BulletProfile`, `ScopeProfile`, and
  `ClickUnit` are deliberate supersets of VTB's (identical Gson field names plus
  STS extensions). Every field VTB's ballistics engine reads
  (`heightAboveBarrelIn`, `maxElevationTravelMoa`, `boresightOffset*`, `massKg`,
  `muzzleVelocityMps`, `crossSectionalAreaM2`, `mvTempCoeff*`, ...) is already
  present, so the ported VTB code binds to the single STS store with **no field
  changes**. One equipment store now serves both ballistics and scoring.
- **`ProfileRepository`, `Logger`** — STS versions are supersets of VTB's; ported
  code binds to them unchanged.
- **`UnitsManager`** — unioned: STS's size/format methods plus VTB's
  `displaySpeed` / `speedUnitLabel` / `displayOffset` / `offsetUnitLabel`.
- **`ThemeManager`, `BaseActivity`, `MainActivity`, `AppBackup`** — STS's are
  canonical; VTB's duplicates were dropped.

### Ported from VTB (renamed `com.rfsat.vtb` -> `com.rfsat.bas`)

`ballistics/`, `capture/`, `wind/`, `environment/` packages; `ui/WindChartView`;
`results/AdjustmentCalculator` and `results/AnalysisSession`. VTB's
`ResultsActivity` was renamed to **`BallisticsResultsActivity`** (and its
view-binding layout `activity_results.xml` -> `activity_ballistics_results.xml` /
`ActivityBallisticsResultsBinding`) to avoid colliding with STS's scoring
`ResultsActivity`. VTB's `AboutActivity`, its `ui.BaseActivity/MainActivity/
ThemeManager/UnitsManager`, and its `ProfileActivity`/duplicate layouts were not
ported.

### Manifest / resources

- Added activities: `capture.CaptureActivity`, `results.BallisticsResultsActivity`.
- Added Kestrel BLE permissions (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
  `neverForLocation`, plus the pre-Android-12 fallbacks) on top of STS's camera/
  network/audio permissions.
- Added strings: `nav_ballistics`, `nav_score`, and the ballistics-flow strings
  `record`, `stop`, `analyze_trail`, `import_video`, `mark_boresight` (VTB shipped
  no `strings.xml` in the source drop, so these were defined fresh).
- New `ic_nav_ballistics` crosshair icon; Score reuses `ic_nav_session`.

### CI

STS's release workflow (`.github/workflows/android-ci.yml`) is reused — it builds
a signed release APK **and** the Play `.aab`, uploads the R8 mapping, and runs the
static checker. Artifact names were changed `sts-*` -> `bas-*`.

## Signing / keystore

Reuse the **same four repository secrets** from STS or VTB in the BAS repo —
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. A keystore/upload key is not bound to a package name and
can sign any number of apps. `applicationId` (`com.BAS`) is the only thing that
must be new. To isolate blast radius you may instead generate a dedicated key for
BAS — the CI is identical either way.

## Verification status — read this before first build

A first CI run of `gradle testReleaseUnitTest` validated the resource,
data-binding and manifest phases (they all passed), and surfaced two symbol
renames the heuristic checks can't see — both now fixed: `StsApp` call sites in
`MainActivity` (the class/file had been renamed `BasApp` but not its references),
and `R.style.Theme_STS_*` in `ThemeManager` (the sed matched the dotted
`Theme.STS` style names but not the underscore R-field form). No `com.rfsat.sts`/
`com.rfsat.vtb` identifier remains in code. What also passed:

- The repo's own static checker `tools/kotlin_checks.py` — **125 files, no
  problems** (semantic, view-binding, import, visibility, and language-level gates).
- Every `.kt` `package` declaration matches its directory path.
- Zero lingering `com.rfsat.sts` / `com.rfsat.vtb` references.
- Every `Activity*Binding` maps to an existing layout; every custom
  `<com.rfsat.bas.*>` view resolves to a class; every ported resource reference
  (`@string`, `@drawable`, `R.*`) resolves.

Two small, correct fixes were made so the checker passes cleanly on the merged
tree: gate-2 (labeled-return) now scans backward over balanced parentheses so it
recognises VTB's `addListener({...}, exec)` and multiline
`registerForActivityResult(...){...}` lambdas (previously mis-reported as invalid
labels); and STS's private `RtspClient.write(...)` was renamed `writeReq(...)` to
remove a cross-file name collision the visibility gate flagged against VTB's
`fuBuf.write(...)`.

### Expected last-mile items on first compile

Open in Android Studio (or push to CI) and expect to resolve a few integration
seams only a real compiler can surface:

1. **`ProfileRepository` API surface** — confirm return types line up
   (e.g. `ProfileSet`) between the ported ballistics/capture callers and STS's repo.
2. **Home does not yet expose Ballistics** beyond the tab bar — Home is STS's
   scoring dashboard. Consider adding a "Put shots on centre" entry.
3. **Unified Results** — currently two screens (scoring `ResultsActivity`,
   ballistics `BallisticsResultsActivity`). Intended design is one Results tab with
   `Adjustment | Group` sub-tabs; that consolidation is a follow-up.
4. **Shared session model** — the merge's real payoff is linking the ballistic
   solution's predicted POI to the scored group's measured POI to close the zero
   loop. `AnalysisSession` and `ScoringSession` are both present but not yet joined.
