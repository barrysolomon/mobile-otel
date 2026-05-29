# Keep ProcessLifecycleOwner so LifecycleInstrumentation can resolve it
# at runtime even under aggressive R8 minification. NoClassDefFoundError
# from a stripped Lifecycle class would silently kill all foreground/
# background telemetry — fail loudly via this rule instead.
-keep class androidx.lifecycle.ProcessLifecycleOwner { *; }
-keep class androidx.lifecycle.ProcessLifecycleInitializer { *; }
