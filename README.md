# BAS — Ballistics and Scoring

An Android application that carries a shooter through the whole of a session:
first putting shots on the centre of the target, then measuring how tightly
they landed. It is the union of two apps built on the same structure and visual
language — **VTB Vapor-Trail Ballistics**, which computes the correction that
brings the point of impact to centre, and **STS Shooting Target Scorer**, which
registers the card and scores the group against the printed rings. BAS keeps
STS as its base and folds VTB's ballistics stack into it, so the same rifle,
load and scope you describe once drive both the trajectory solution and the
scoring gauge.

- `applicationId` — `com.BAS` (permanent once published)
- Kotlin namespace — `com.rfsat.bas`
- AGP 8.9.1 / Kotlin 2.1.0 / compileSdk 36 / minSdk 26 / targetSdk 36
- Gradle 8.11.1 or newer, JDK 17

Open the folder in Android Studio and build. The **User Guide** and **Programmer's Reference** are in `docs/`. See `INTEGRATION.md` for exactly
how the two apps were merged and what remains for the first compile pass.

---

## Navigation

Five bottom tabs, capped at Material's limit of five, ordered left to right as
the work actually flows: get on centre, then grade the placement.

| Tab | Screen | Origin |
|---|---|---|
| **Home** | session dashboard and crash-safe startup | STS |
| **Ballistics** | capture the shot, read wind, compute the scope correction | VTB |
| **Score** | photograph the card, detect holes, score the rings | STS |
| **Results** | group centre, dispersion, and the correction it implies | STS |
| **Settings** | profiles, target faces, rules, log, backup | STS |

Targets, competition rules, the diagnostic log and backup/restore live under
Settings; Exit sits outside the capped menu. Every tab is reachable by tap or
by horizontal swipe.

---

## What it does

### Ballistics — put shots on centre

1. **Describe the rig once.** Rifle, load and scope profiles — shared with the
   scoring side, so nothing is entered twice.
2. **Bring in conditions.** Temperature, pressure and humidity from a Kestrel
   weather meter over Bluetooth, or entered by hand; each feeds the trajectory.
3. **Capture the shot.** Phone camera, an imported video, or a Wi-Fi RTSP feed
   from a scope or action camera.
4. **Read the wind.** Crosswind is estimated from the bullet's vapour trail, or
   from a tracer's lag deflection, and folded into the solution.
5. **Get the correction.** In clicks for the turret in the active profile — MRAD
   or MOA — for telescopic sights, diopters and irons alike.

### Scoring — grade the group

1. **Register the target.** The scoring area is found and squared to a
   millimetre grid; everything downstream works in millimetres.
2. **Capture a clean reference,** then shoot, and each new hole is found by
   differencing against it.
3. **Score against the face,** under the conventions of the selected rule set,
   with a running total and group statistics.
4. **Read the correction** the group implies — clicks on the sight, or a
   rear-sight movement in millimetres for sights with no clicks.

### Ways to record a shot

| Mode | When | Notes |
|---|---|---|
| **Live** | camera stays on the target for the string | immune to printed rings, paper texture and the aiming mark |
| **From a photograph** | the relay is over and the card is in hand | best with a clean "before" photo, since scoring is then a difference |
| **Single frame** | scoring the target in front of the camera now | as above, from the live feed |
| **By hand** | anything the detector got wrong | authoritative — tap the plot on Results |

### Equipment, once

The rifle, ammunition and scope are described once and shared by both sides of
the app. The profile format keeps the same Gson field names as VTB and DBM, so
a profile set exported from any of them imports unchanged. Save a set per rig
and switch between them in a tap.

---

## Built for the firing point

Dark and night-red themes to protect dark adaptation; full-screen, glance-able
layouts; metric or imperial throughout; a diagnostic log and a full
backup/restore of everything set up. Persistence is Gson in SharedPreferences,
and startup is crash-safe — a failure in shared chrome can never kill the
screen it merely decorates.

---

## Continuous integration and signing

`.github/workflows/android-ci.yml` is **release only** — it never assembles a
debug artefact. It runs the static checks and unit tests against the release
variant, then builds the release APK and the Android App Bundle for Play, and
uploads both along with the R8 mapping file.

Configure four repository secrets and the artefacts come out signed and
uploadable:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias |
| `ANDROID_KEY_PASSWORD` | key password |

The **same key used for STS or VTB may sign BAS** — a keystore is not bound to a
package name, and only `applicationId` (`com.BAS`) has to be new. Without the
secrets the build still succeeds and produces both artefacts unsigned — useful
on a fork, useless for Play — and the run summary states which of the two you
got. The keystore is deleted from the runner immediately after the build.

Keep the R8 mapping file: R8 rewrites the release build, so a crash report from
it is unreadable without `mapping.txt`, and the per-build artefact keeps an old
release diagnosable after the next has overwritten what Play holds.

---

## Verification

`tools/kotlin_checks.py` is a cheap semantic pre-check run first in CI — it
gates the merged tree on package/path agreement, view-binding ids, imports,
cross-file visibility, `when`-over-enum coverage, and language level, naming the
file and line before the same mistakes reach the compiler as errors reported
somewhere else. The unit tests then run against the release variant, so they
test the code the release ships without a debug build to do it.

---

## Versioning and packaging

`<brand>.<major>.<minor>` — the scheme both parent apps use.

| Component | When it changes |
|---|---|
| **brand** | never; `1` = BAS |
| **major** | a feature is added (minor resets to 0) |
| **minor** | a correction is made |

`versionCode` increments on **every** build that leaves the development machine.
Play rejects a bundle whose code is not strictly greater than the last uploaded
one. Each release ships as a **single ZIP** holding the whole project —
`BAS_v<brand>_<major>_<minor>.zip`.

### Changelog

One entry per release, newest first. The full entry for each release is written
in the header comment of `app/build.gradle.kts` as the work is done.

**1.20.1** — correction. The wind field was the wrong one: 1.20.0 read @4 of the LiNK record, which holds the not-measured sentinel in every frame. Wind speed is @0 (×100 m/s) — 0 with the impeller still, 444 (4.44 m/s) when turning, matching the meter's own ~4.5 m/s. Zero is now reported as "calm" rather than "not measured".

**1.20.0** — feature. The Kestrel's wind is now read (speed and direction from the LiNK record) and used as the wind at the firing point: it joins the weighted average as an anchor at zero downrange while the vapour trail supplies how the wind changes down the range. A still impeller reports NK's not-measured sentinel, which is kept as "wind not measured" rather than shown as calm. Direction is read as wind-from, with the line of fire either implied (meter pointed downrange) or entered as a bearing.

**1.19.1** — corrections. A meter now outranks the phone per quantity, so re-entering the Ballistics tab no longer replaces a Kestrel pressure with the phone's barometer. And the Kestrel range bridge stopped reading weather as range: NK's not-measured sentinels are refused, the LiNK weather characteristics are excluded from range scanning, and a candidate must change from the standing value and repeat before it is offered. Locks learned by the old decoder are discarded.

**1.19.0** — feature. The Kestrel 5700 Elite now works: it speaks NK's LiNK service (03290000-…), which nothing parsed, so it read every characteristic and yielded nothing. The measurement record in 03290310 is decoded (temperature, humidity, station pressure, with NK's not-measured sentinels rejected), verified against a real 5700AL-R log. Settings also gains an "Environmental devices" section — Automatic, Phone sensors, Kestrel 5700 Elite or Kestrel DROP D3 — so owning two meters no longer means taking whichever answers first.

**1.18.1** — corrections. Range mode and the Ballistics overlay now lead with the correction as an angle (windage and elevation on separate lines) with clicks as the caption, matching the Ballistics results screen; the Scoring correction line moved out of the viewfinder so it cannot overlap the notes; the Ballistics preview matches the Scoring one; and Settings was reorganised — "Other options" moved down, Load → Ammunition, Sight → Optics and Sights, service keys folded into "AI-assisted processing", the version line moved outside the sections, and "Reset to defaults" is now a full factory reset.

**1.18.0** — feature. A Welcome screen on first run and after a full reset: six steps covering the working mode (ballistics, scoring, or both in sequence), the camera, the distance source, a weather meter and AI assistance, writing the matching configuration. Skip takes the defaults; it can be re-run from Settings. In "both" mode the ballistics results offer "Score the target" as the next step. The guide and reference are now built on the STS template (styles, RFSAT logo, header and page numbers).

**1.17.1** — documentation. `docs/` now holds the BAS User Guide and Programmer's Reference (editable .docx plus published PDF). Both integrate their predecessors the way the app does: the guide takes STS's structure and folds in VTB's ballistics chapters, then adds the camera, rangefinder and Range-mode material; the reference extends VTB's with the STS scoring half and everything since. See `docs/guide/README.md` for how they are maintained.

**1.17.0** — feature + corrections. Content is padded clear of a top-centre camera cutout; Range mode now shows the correction in MRAD/MOA as well as clicks; the reticle colour comes from one high-contrast theme attribute shared by both viewfinders (with a dark halo so it reads over paper and aiming mark alike); and Settings sections start collapsed.

**1.16.0** — feature. Rangefinder support three ways: bridged through a Kestrel 5700 Elite (covering Leica, Vortex and SIG BDX-X without decoding any vendor protocol), direct BLE links for SIG KILO, Leica, Vortex, Vectronix Terrapin-X, FIRE4000 and a generic catch-all, or entered by hand. Since no vendor publishes a GATT profile the decoder is generic — and because a heuristic can read a temperature as a range, the first reading is confirmed against the display and the characteristic and unit scale are then locked. New "Rangefinder and distance" section in Settings.

**1.15.1** — correction. Added `.gitattributes`: text normalised to LF in the repository, with `.sh`, `.py` and `gradlew` pinned to LF on checkout (a shell script with CRLF fails on Linux) and binaries marked so Git never rewrites them.

**1.15.0** — feature. Opening "Profile sets" also opens Firearm, Load and Sight; a new seeded set (Ruger Precision Rimfire .22LR + Federal Champion 40gr + Vector Optics Continental 5-30x56) is the default on a fresh install, so the first-run picker is gone; "Elsewhere" became "Other options", with Backup and reset, Target faces and Competition rules as their own sections; and the launcher icon is regenerated from the BAS mark so the home screen and the Play listing match.

**1.14.0** — feature + corrections. A Distance menu on both tabs (type it, use the zero/calibration distance — now editable and saved to the rig — or run the FIRE4000 BLE probe); Settings sections start open, so the rangefinder entry is visible; Kestrel readings survive a restart; the phone preview no longer comes up black on Ballistics (camera rebinds every resume); Range mode fonts rebalanced and the scoring headline enlarged; Range options labels fit one line; the version moved from Home to the foot of Settings; and the Home text now promotes long-range shooting.

**1.13.1** — correction. Fixed a resource build break: the reworded app description contained a bare apostrophe, which aapt2 rejects inside a `<string>`. Escaped it, and added a static check (gate 10) that validates every `res/values/*.xml` and flags unescaped apostrophes or quotes.

**1.13.0** — feature. A BLE discovery probe for a laser rangefinder (Tangoinnos FIRE4000), reached from Settings. Its protocol is unpublished, so the probe finds the device (bonded or advertising), enumerates every service/characteristic, reads what is readable, subscribes to everything that notifies, and logs each frame as hex plus candidate integer readings — range a target while it listens and the distance is identifiable in the Log. Reads and subscriptions only; no new permissions.

**1.12.0** — feature + fixes. Home/about text says BAS only; ballistic results now survive a restart (`AnalysisSession.restore` at startup); Range mode leads with the wind correction in the largest font; Settings sections fold under their headings (tap to open); both viewfinder crosshairs are the same size; and target distance gains a "= zero" quick-fill to the rig's calibration distance. FIRE4000 rangefinder integration investigated (Bluetooth, undocumented protocol — needs a BLE discovery probe).

**1.11.0** — feature. Three opt-in hands-free tools (Settings → Range options): auto-collect (armed standby that polls the camera and auto-analyses any new clip — so recording with the camera's own remote flows straight into processing); remote trigger (volume/Bluetooth-shutter keys fire the screen's primary action); and confirmation-free operation (skips the clear-shots and remove-marks prompts). New peek/download helpers on `CameraFileImporter`; `BaseActivity.onRemoteTrigger`.

**1.10.2** — correction. Fixed a build break: `CameraWifi` referenced `RangeSettings` from another package unqualified. Fully qualified it, and added a static check (gate 9) that flags any top-level object/class used unqualified across packages.

**1.10.1** — refinement. Settings → Range options adds "Auto-reconnect camera Wi-Fi" (waits longer for the AP) and "Auto-advance to results after each shot" (opt-in). Live scoring now speaks each shot and the running correction when speech is on. Keep-awake was already an option and the camera selection is already remembered.

**1.10.0** — feature. Prone-shooter usability: a full-screen Range mode glance screen (big arrows+clicks correction and large score, launched from Home); optional spoken corrections and scores via text-to-speech (default OFF, toggled in Settings → Range options); keep-screen-on during a session (default ON); and a last-correction overlay on both live viewfinders (wind on Ballistics, dial-to-centre on Score). New `RangeActivity`/`Speaker`/`RangeSettings`/`Corrections`.

**1.9.0** — feature. One consistent camera selector on both the Ballistics and Score tabs: a "Camera: <type>" button (Phone, GoPro HERO9+, TACTACAM, ShotKam, RTSP/MJPEG) and a "Configure" button that shows only the options the selected camera supports. Settings gains "Camera defaults…" for the default type and each camera's host. New `CameraType`/`CameraConfig`/`CameraUi`; the standalone GoPro buttons on Score folded into this.

**1.8.0** — feature + corrections. The Ballistics viewfinder now draws the reticle chosen in Settings (shared `ReticleDrawer`), and two new reticles were added — MOA and MRAD wind trees with numbered holdover bars. The phone preview uses CameraX COMPATIBLE mode to avoid intermittent blank frames. The Results Scoring | Ballistics switch shows as real buttons; "Training — free practice" is now "Training practice"; and the "Score" tab is titled "Scoring". Next: a shared camera-type selector with a Configure button (only supported options) and per-camera defaults in Settings.

**1.7.1** — correction. Ballistics/Capture usability: the camera controls now live in a ScrollView so the preview keeps its size and the rest scrolls (bottom nav pinned); the four borderless camera links became one real "Camera ▾" button that opens a menu; the bottom Results tab opens the ballistics results when on the Ballistics flow (scoring elsewhere), and the Results subhead compacts the rule name (e.g. "Training — free practice" → "Training").

**1.7.0** — feature. GoPro live preview on both the Ballistics/Capture and Score screens. GoPro (HERO9+) serves a low-res MPEG-TS preview over UDP 8554 (not RTSP); the new `GoProPreviewStream` binds the camera Wi-Fi, demuxes the transport stream to H.264 and decodes it to a Surface with MediaCodec, plus keep-alive. Capture renders to its SurfaceView (aiming), the Score screen to its TextureView (observation). Preview is 480p (HERO9) / 720p (HERO11), so scoring still uses a downloaded full-res still.

**1.6.2** — correction. Fixed a build break: the GoPro-photo-to-Score extra was declared above ImportActivity's imports, which Kotlin rejects. Moved below the imports, and added a static check (gate 8) that flags any import placed after a top-level declaration.

**1.6.1** — correction. GoPro support targets HERO9 Black and later (all Open GoPro), so no legacy path is needed; digital zoom now tries both the `percent=` and `range_pcnt=` parameter names, which differ across HERO9/10 firmware.

**1.6.0** — feature. GoPro still straight into scoring. The Score screen gains "Score latest photo from GoPro", which binds the GoPro Wi-Fi, pulls the newest still from the Open GoPro media list, and feeds it to the existing target registration/scoring flow (the same `onImagePicked` pipeline a gallery photo uses). Set the GoPro zoom first from the Ballistics tab's GoPro menu for distant targets.

**1.5.0** — feature. GoPro support via the official Open GoPro HTTP API — the easy case, since GoPro documents its protocol. A "GoPro" button on Capture works over the camera Wi-Fi (10.5.5.9:8080): download the latest clip (from the `/gopro/media/list` JSON, newest video preferred), start/stop recording, set digital zoom, load a preset, read camera state, and keep-awake — so the GoPro can be configured from inside BAS, not just read. Its high resolution and zoom also suit scoring; a downloaded still can be imported on the Score screen (a direct GoPro-photo-to-Score path is next).

**1.4.0** — feature. A wide-net camera discovery scan, so the route to the files is found in-app without the manufacturer app or a packet capture. "Scan camera (discover)" on Capture binds the camera Wi-Fi, detects the gateway, TCP-sweeps candidate hosts/ports, then deep-probes what answered (Novatek CGI set, GoPro-style paths, DCIM/MOVIE trees, Ambarella 7878 and RTSP 554), logs and summarises every result, and offers a one-tap "Download newest" when a listing yields media. Broad by design until the exact endpoints for TACTACAM and ShotKam are confirmed.

**1.3.0** — feature. Download the newest clip straight off the camera's Wi-Fi, with presets for TACTACAM 5.0 and ShotKam Gen 4. Both serve their SD card over their own Wi-Fi AP but neither documents the protocol, so `CameraFileImporter` probes the endpoints common action-cam chipsets expose (Novatek `cmd=3015`, an HTTP DCIM listing), takes the newest media file, downloads it and hands it to the analyzer — logging every request. A "Download latest from camera" button on Capture offers a preset picker with an editable host; the TACTACAM/ShotKam addresses are best guesses flagged "verify" until a field capture confirms them.

**1.2.0** — feature. Both results are now reachable from the Results tab: each results screen carries a Scoring | Ballistics (wind) switch at the top, and the ballistics results screen highlights the Results tab, so the wind chart and the scoring plot are one tap apart.

**1.1.0** — feature. Makes the integrated app behave like STS and VTB out of the box. The detection/scoring/vapour-trail code is byte-identical to the originals (verified by normalised diff); the reported regressions all traced to the unified profile store auto-applying its first seeded set (10 m air rifle) as the active rig, under which the ballistic solver never reaches a rifle target (so the wind chart is suppressed) and scoring registers a different box. Adds a one-time first-run rig picker on Home (six seeded rigs from 10 m air to .308 F-TR), a "View last analysis" button so the wind chart is reachable beyond the post-capture moment, and a tappable Home Target row that opens the face selector.

**1.0.1** — correction. Two symbol renames caught by the first CI compile: `StsApp` call sites in `MainActivity` (the class was renamed `BasApp` but not its references) and `R.style.Theme_STS_*` in `ThemeManager` (the rename matched the dotted `Theme.STS` style names but not the underscore R-field form). Both fixed; the resource, data-binding and manifest phases had already passed.

**1.0.0** — first release: the integration itself. BAS merges STS and VTB into
one application. STS is the base; VTB's `ballistics`, `capture`, `wind` and
`environment` packages are folded in under `com.rfsat.bas`, and its ballistics
results screen is renamed `BallisticsResultsActivity` to sit beside STS's
scoring results without collision. The equipment store is unified — STS's
profile classes were already field-supersets of VTB's, so one rifle/load/scope
set now drives both the trajectory solution and the scoring gauge — and
`UnitsManager` is unioned to carry both size/format and speed/offset. A new
five-tab shell (Home · Ballistics · Score · Results · Settings) replaces the two
separate navigations; Targets, Rules, Log and Backup move under Settings.
`applicationId` is `com.BAS`, starting at `versionCode 1`.

### Lineage

BAS inherits two mature codebases. A compressed history of each, newest first,
for context — the full per-release detail lives in each parent's own history.

**From STS (up to 1.57.0) — scoring and target registration**
- Wi-Fi camera settings recorded and used: a declared red dot suppresses the
  false hit it would otherwise plant at the ten ring; the stream size is checked
  against what was declared.
- A hand-written RTSP client (TCP interleaved, SDP parsed, RTP to MediaCodec)
  with every handshake step logged, after three releases proved the fault was
  the network route, not the decoder.
- Lens distortion measured from the printed rings themselves; a reticle library;
  AI second-opinion scoring as an optional arbiter of doubtful holes.
- The core: projective registration to a millimetre grid, difference-based hole
  detection, ring/zone scoring, group statistics, and sight corrections.

**From VTB (up to 1.20.45) — ballistics and wind**
- Crosswind estimated from the bullet's vapour trail, with a tracer-lag path for
  tracer rounds and a pellet-tracking path for airguns.
- Kestrel weather-meter link over BLE (bonded connect, advertising-only scan for
  the DROP D3), feeding live atmosphere into the solver.
- Scope-recorded and Wi-Fi-streamed capture sources, with per-scope field-of-view
  geometry for digital optics.
- The core: a drag-model trajectory engine solving launch pitch for the zero and
  converting the point-of-impact offset into turret clicks.
