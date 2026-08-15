// BAS — Ballistics and Scoring.
//
// Toolchain: AGP 9.0.0 / Gradle 9.7.0 / compileSdk 36. The Kotlin version is
// AGP's own now — see below.
//
// AGP 9 is a Google Play recommendation, not merely housekeeping: the
// optimisation report asks for it directly. It brings two hard requirements
// with it —
//   * Gradle 9.x, so .github/workflows/android-ci.yml pins that too. The two
//     versions must move together; AGP 9 on Gradle 8 fails at configuration
//     time with a message about the minimum supported version.
//   * kotlinOptions {} is removed. app/build.gradle.kts uses the Kotlin
//     compilerOptions DSL instead, which is valid on 8.9.1 as well.
//   * BUILT-IN KOTLIN. AGP 9 compiles Kotlin itself and registers the
//     `kotlin` extension, so the org.jetbrains.kotlin.android plugin must NOT
//     be applied — doing so fails with "Cannot add extension with name
//     'kotlin'". It is removed here and from the module file, which also
//     means the Kotlin version is no longer pinned in this project: it is
//     whichever version AGP 9.0.0 embeds.
//
// Per Google's migration guide the other two steps do not apply to this
// project: it uses neither kapt (which would need com.android.legacy-kapt)
// nor the kotlin.sourceSets DSL.
//
// IF THIS BUILD FAILS, the failure says which half:
//   * "Plugin [id: 'com.android.application', version: '9.0.0'] was not
//     found" or an unresolved Gradle distribution — the VERSIONS are wrong,
//     and reverting the two numbers below plus the workflow pin returns to a
//     known-good state while keeping the compilerOptions migration.
//   * anything about the DSL, a removed property or a variant API — the
//     MIGRATION is incomplete, and the version numbers are fine.
plugins {
    id("com.android.application") version "9.0.0" apply false
}
