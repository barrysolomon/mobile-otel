# Dash0 Mobile OTel SDK — library ProGuard rules
# Applied when building the library module itself with minification.
# Consumer rules (consumer-rules.pro) are the primary mechanism for host apps.

# Keep SDK public API surface for external consumers
-keep public class io.opentelemetry.android.mobile.** { public *; }
-keep public class io.opentelemetry.android.mobile.core.** { public *; }
