# Dash0 Mobile OTel SDK Core — consumer ProGuard rules

# ── Core interfaces (used by reflection in InstrumentationRegistry) ───────────
-keep class * implements io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation { *; }
-keep interface io.opentelemetry.android.mobile.instrumentation.MobileInstrumentation { *; }
-keep @io.opentelemetry.android.mobile.instrumentation.Supersedes class * { *; }

# ── InstrumentationContext (passed to modules at install time) ────────────────
-keep class io.opentelemetry.android.mobile.instrumentation.InstrumentationContext { *; }
-keep class io.opentelemetry.android.mobile.core.PiiScrubber { *; }

# ── Breadcrumbs and navigation ────────────────────────────────────────────────
-keep class io.opentelemetry.android.mobile.breadcrumb.** { *; }
-keep class io.opentelemetry.android.mobile.navigation.** { *; }
