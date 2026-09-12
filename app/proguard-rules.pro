# >>> FILE: app/proguard-rules.pro
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-dontobfuscate

# 1. Защита нативных точек входа C++ JNI (libclient_core.so)
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.client.app.audio.NativeAudioBridge { *; }

# 2. Google Protobuf Lite (генерация схем Bidi Streaming)
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keep class com.google.ai.generativelanguage.v1beta.** { *; }

# 3. Microsoft ONNX Runtime (Snapdragon NPU / Hexagon HTP v73)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# 4. Системные медиа-сессии (защита от засыпания One UI App Freezer)
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }
-keep class androidx.media.app.** { *; }

# 5. Сериализация Kotlinx
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class *
-keepclassmembers class * {
    @kotlinx.serialization.SerialName *;
}

# 6. Корутины и каналы
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# 7. OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# 8. Dagger Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { <init>(...); }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# 9. Jetpack Compose Runtime
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keepclassmembers class androidx.compose.** {
    <init>(...);
}

# 10. Доменные сущности приложения
-keep class com.client.app.session.** { *; }
-keep class com.client.app.api.** { *; }
-keep class com.client.app.audio.** { *; }
-keep class com.client.app.vad.** { *; }
-keep class com.client.app.haptics.** { *; }
-keep class com.client.app.ui.display.** { *; }