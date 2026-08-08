# BAS — Ballistics and Scoring

An integrated Android app combining **VTB** (Vapor-Trail Ballistics: put shots on
centre) and **STS** (Shooting Target Scorer: grade the group) into one product.

- Play `applicationId`: `com.BAS`  ·  namespace `com.rfsat.bas`  ·  v1.0.0 (code 1)
- Tabs: **Home · Ballistics · Score · Results · Settings**
- Build in CI via `.github/workflows/android-ci.yml` (signed APK + Play `.aab`).

See **INTEGRATION.md** for how the two apps were merged, the keystore/signing
guidance, and the verification status. Configure the four `ANDROID_KEYSTORE_*`
repository secrets (reusable from STS or VTB) to get signed artifacts.
