-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod
-dontobfuscate

# Сохранение нативных точек входа JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.client.app.audio.NativeAudioBridge { *; }

# Google Protobuf Lite
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
-keep class com.google.ai.generativelanguage.v1beta.** { *; }

# Microsoft ONNX Runtime (QNN HTP & CPU)
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Сериализация Kotlinx
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class *
-keepclassmembers class * {
    @kotlinx.serialization.SerialName *;
}

# Корутины
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Dagger Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { <init>(...); }
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
}

# Compose Runtime
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keepclassmembers class androidx.compose.** {
    <init>(...);
}

# Доменная модель сессии
-keep class com.client.app.session.** { *; }
-keep class com.client.app.api.** { *; }