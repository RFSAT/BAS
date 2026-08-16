# ---------------------------------------------------------------------------
#  What R8 may and may not touch in this app
#
#  This used to be one line — -keep class com.rfsat.bas.** { *; } — which kept
#  EVERY class and EVERY member of the entire app. It was safe by being blunt,
#  and Play measured the result: 44% optimisation, 45% obfuscation, 45%
#  shrinking. R8 was being paid for and not allowed to work.
#
#  The real constraint is narrower than that rule. Every persisted format here
#  is Gson reflection over FIELD NAMES, so renaming a field silently changes a
#  stored JSON key — no crash, no build error, just data that stops loading.
#  But that constraint applies to FIELD NAMES IN THE MODEL CLASSES ONLY. Class
#  names may be obfuscated freely (Gson serialises fields, not class names),
#  and the detector, the solver, the camera code and every screen may be
#  optimised and renamed like any other code.
#
#  The list below was derived by auditing every fromJson/toJson/TypeToken call
#  site, not by guessing which packages sounded like data. Anything added to
#  those packages inherits the protection; a NEW package that gets persisted
#  must be added here, and the symptom of forgetting will be silent data loss
#  in release builds only.
#
#  VERIFICATION: CI cannot prove this. Unit tests run on unminified classes,
#  and a release build that links is not evidence that Gson still round-trips.
#  It has to be checked by installing the release build over existing data and
#  confirming profiles, targets, rules and a saved session all still load.
# ---------------------------------------------------------------------------

# Field names are the storage format for these packages.
-keepclassmembers class com.rfsat.bas.profiles.** { <fields>; }
-keepclassmembers class com.rfsat.bas.targets.** { <fields>; }
-keepclassmembers class com.rfsat.bas.rules.** { <fields>; }
-keepclassmembers class com.rfsat.bas.scoring.** { <fields>; }
-keepclassmembers class com.rfsat.bas.results.** { <fields>; }
-keepclassmembers class com.rfsat.bas.backup.** { <fields>; }
-keepclassmembers class com.rfsat.bas.wind.** { <fields>; }
-keepclassmembers class com.rfsat.bas.capture.CameraConfig { <fields>; }

# Enum CONSTANT names are persisted twice over: Gson writes the name into the
# JSON, and several settings are read back with valueOf() on a stored string
# (SightType, FirearmType, AppMode, WeatherTier, Position, the translation
# provider). An obfuscated enum constant breaks both.
-keepclassmembers enum com.rfsat.bas.** { *; }

# Custom views are instantiated BY NAME from the layout XML. AGP generates
# keep rules for these from the merged resources, but stating it here means
# the guarantee does not depend on that generation continuing to work.
-keep class * extends android.view.View {
    <init>(android.content.Context);
    <init>(android.content.Context, android.util.AttributeSet);
    <init>(android.content.Context, android.util.AttributeSet, int);
}

# Gson's own requirements
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Kotlin metadata used by data-class copy()/component1() reflection
-keep class kotlin.Metadata { *; }

# ---------------------------------------------------------------------------
#  androidx.security:security-crypto -> Google Tink
#
#  Tink is compiled against JSR-305 annotations (javax.annotation.Nullable,
#  javax.annotation.concurrent.GuardedBy) that Android does not ship and that
#  nothing needs at run time — they are source and class retention hints for
#  static analysers. R8 refuses to shrink while it cannot resolve them, which
#  fails the RELEASE build only: the debug build does not run R8, so this was
#  invisible until assembleRelease.
#
#  Warning them away rather than adding a compile-only dependency on JSR-305:
#  nothing in this app reads those annotations, and pulling in a library to
#  satisfy a reference that is never dereferenced adds a dependency for no
#  behaviour.
# ---------------------------------------------------------------------------
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

#  ...and Tink's OPTIONAL dependencies. KeysDownloader fetches JWT public
#  keys over HTTP and wants the Google HTTP client and Joda-Time to do it.
#  Nothing in EncryptedSharedPreferences ever calls it, and neither library
#  is on the classpath, so R8 sees dangling references and stops.
#
#  Note WHY this had to be said at all: the -keep below deliberately keeps
#  every Tink class, which is what stops R8 discarding KeysDownloader as
#  unreachable and lets it notice the missing references. Narrowing the keep
#  would make the warning disappear, and would also risk R8 stripping a key
#  manager that Tink loads by name — a crash on first use of encrypted
#  storage, which is far worse than a build that will not link. So the keep
#  stays broad and the unused optional libraries are warned away.
-dontwarn com.google.api.client.**
-dontwarn com.google.api.http.**
-dontwarn org.joda.time.**
-dontwarn com.google.errorprone.annotations.**

# Tink loads its key managers and protobuf message classes by name, so R8
# cannot see the references and would strip them.
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}

# Media3's own consumer rules ship with the library and are enough for it.
# These are for the ANNOTATION-ONLY dependencies it and Guava reference and
# neither ships — the same class of R8 failure that broke the release build
# twice over encrypted storage. Warned away rather than satisfied with
# another dependency, because nothing dereferences them at run time.
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn java.lang.instrument.**
-dontwarn sun.misc.**
