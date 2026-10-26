# JNI: libforgespawn.so resolves these by name.
-keep class com.nextcraft.forge.runtime.NativeSpawn { native <methods>; }
# Termux terminal emulator: libtermux.so resolves com.termux.terminal.JNI by name.
-keep class com.termux.** { *; }
# Resolved reflectively by AppUpdater.
-keep class com.nextcraft.forge.BuildConfig { *; }
# Resolved reflectively by name (Class.forName).
-keep class com.nextcraft.forge.MainActivity
-keep class com.nextcraft.forge.runtime.RuntimeExecutionService
-keep class com.nextcraft.forge.runtime.RuntimeSetupService
# Archive/compression libraries use optional codecs that are not bundled.
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-keep class com.github.luben.zstd.** { *; }
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
