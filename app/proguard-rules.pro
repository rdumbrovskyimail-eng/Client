# >>> FILE: app/proguard-rules.pro

# Keep metadata required by Kotlin/JVM reflection, serialization and generated
# code where applicable.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod

# ---------------------------------------------------------------------------
# 1. JNI
# ---------------------------------------------------------------------------
# JNI methods are resolved by their Java/Kotlin class and native method names.
# Keep the bridge class and all of its members stable.
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.client.app.audio.NativeAudioBridge {
    *;
}

# ---------------------------------------------------------------------------
# 2. Kotlinx Serialization
# ---------------------------------------------------------------------------
# Preserve annotation/serializer metadata used by kotlinx.serialization.
-dontnote kotlinx.serialization.AnnotationsKt

-keep @kotlinx.serialization.Serializable class * {
    *;
}

-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# ---------------------------------------------------------------------------
# 3. Hilt / javax.inject
# ---------------------------------------------------------------------------
# Hilt normally supplies the necessary consumer rules. These application-side
# rules retain only injection entry points that are selected by annotations.
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * {
    <init>(...);
}

-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# ---------------------------------------------------------------------------
# 4. Kotlin coroutines
# ---------------------------------------------------------------------------
# Keep dispatcher factory / handler names required by ServiceLoader-style
# discovery.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler

# ---------------------------------------------------------------------------
# 5. Logging diagnostics
# ---------------------------------------------------------------------------
# Preserve source locations in diagnostic builds/reports.
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------------
# 6. Do NOT add package-wide keep rules here.
#
# The following broad rules were intentionally removed:
#
# -dontobfuscate
# -keep class ai.onnxruntime.** { *; }
# -keep class okhttp3.** { *; }
# -keep interface okhttp3.** { *; }
# -keep class dagger.hilt.** { *; }
# -keep class javax.inject.** { *; }
# -keep class androidx.media.** { *; }
# -keep class androidx.media.app.** { *; }
# -keep class androidx.compose.runtime.** { *; }
# -keep class androidx.compose.ui.** { *; }
# -keep class com.client.app.session.** { *; }
# -keep class com.client.app.api.** { *; }
# -keep class com.client.app.audio.** { *; }
# -keep class com.client.app.vad.** { *; }
# -keep class com.client.app.haptics.** { *; }
# -keep class com.client.app.ui.display.** { *; }
# -keep class com.client.app.logging.** { *; }
#
# Libraries above provide their own consumer/R8 rules where required.
# Application classes should remain shrinkable/obfuscatable unless a concrete
# runtime contract requires retention.