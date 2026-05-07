# Dash0 Mobile OTel SDK — consumer ProGuard rules
# These rules ship inside the AAR and apply to any app using the SDK with R8/ProGuard.

# ── Public API ────────────────────────────────────────────────────────────────
-keep class io.opentelemetry.android.mobile.OTelMobile { *; }
-keep class io.opentelemetry.android.mobile.OTelMobileBuilder { *; }
-keep class io.opentelemetry.android.mobile.OTelMobileHandle { *; }
-keep class io.opentelemetry.android.mobile.MobileOtel { *; }
-keep class io.opentelemetry.android.mobile.config.MobileConfig { *; }
-keep class io.opentelemetry.android.mobile.config.MobileConfig$Builder { *; }
-keep class io.opentelemetry.android.mobile.autocapture.AutoCaptureOptions { *; }

# ── OpenTelemetry SDK interfaces (reflection-based service loading) ───────────
-keep class io.opentelemetry.** { *; }
-dontwarn io.opentelemetry.**

# ── Room database (entities, DAOs, generated code) ────────────────────────────
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# ── Kotlin Serialization ─────────────────────────────────────────────────────
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Instrumentation modules (loaded by InstrumentationRegistry via reflection) ─
-keep class * implements io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation { *; }
-keep class * implements io.opentelemetry.android.instrumentation.AndroidInstrumentation { *; }

# ── gRPC / OkHttp (network layer) ────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn io.grpc.**
-keep class io.grpc.** { *; }

# ── Coroutines ────────────────────────────────────────────────────────────────
-dontwarn kotlinx.coroutines.**
