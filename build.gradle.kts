// BAS — Ballistics and Scoring.
//
// Toolchain: AGP 9.0.0 / Kotlin 2.1.0 / compileSdk 36.
//
// AGP 9 is a Google Play recommendation, not merely housekeeping: the
// optimisation report asks for it directly. It brings two hard requirements
// with it —
//   * Gradle 9.x, so .github/workflows/android-ci.yml pins that too. The two
//     versions must move together; AGP 9 on Gradle 8 fails at configuration
//     time with a message about the minimum supported version.
//   * kotlinOptions {} is removed. app/build.gradle.kts now uses the Kotlin
//     plugin's compilerOptions instead, which is valid on 8.9.1 as well.
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
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}
