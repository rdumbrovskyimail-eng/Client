# >>> FILE: app/proguard-rules.pro
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-dontobfuscate

# 1. Защита нативных точек входа C++ JNI (libclient_core.so)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.client.app.audio.NativeAudioBridge { *; }

# 2. Microsoft ONNX Runtime (Snapdragon NPU / Hexagon HTP v73)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# 3. Системные медиа-сессии (защита от засыпания One UI App Freezer)
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }
-keep class androidx.media.app.** { *; }

# 4. Сериализация Kotlinx
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class *
-keepclassmembers class * {
    @kotlinx.serialization.SerialName *;
}

# 5. Корутины и каналы
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# 6. OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# 7. Dagger Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { <init>(...); }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# 8. Jetpack Compose Runtime
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keepclassmembers class androidx.compose.** {
    <init>(...);
}

# 9. Доменные сущности приложения
-keep class com.client.app.session.** { *; }
-keep class com.client.app.api.** { *; }
-keep class com.client.app.audio.** { *; }
-keep class com.client.app.vad.** { *; }
-keep class com.client.app.haptics.** { *; }
-keep class com.client.app.ui.display.** { *; }

# 10. Подсистема сквозного логирования (AppLogManager)
-keep class com.client.app.logging.** { *; }
-keepenum class com.client.app.logging.LogLevel { *; }
-keepattributes SourceFile,LineNumberTable