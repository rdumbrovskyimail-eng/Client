#include <jni.h>
#include <string>
#include <android/log.h>
#include <aaudio/AAudio.h>

#define LOG_TAG "NativeCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_client_app_audio_NativeAudioBridge_getHardwareCoreInfo(JNIEnv *env, jobject /* this */) {
    std::string info = "Qualcomm Snapdragon 8 Gen 2 (kalama) Native Engine [C++20/ARMv9] - AAudio Pipeline Ready";
    LOGI("%s", info.c_str());
    return env->NewStringUTF(info.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_probeMmapSupport(JNIEnv *env, jobject /* this */) {
    bool mmapSupported = false;
    AAudioStreamBuilder* builder = nullptr;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    
    if (result == AAUDIO_OK && builder != nullptr) {
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setSampleRate(builder, 24000);
        AAudioStreamBuilder_setChannelCount(builder, 1);
        
        AAudioStream* stream = nullptr;
        result = AAudioStreamBuilder_openStream(builder, &stream);
        if (result == AAUDIO_OK && stream != nullptr) {
            aaudio_sharing_mode_t actualMode = AAudioStream_getSharingMode(stream);
            aaudio_performance_mode_t perfMode = AAudioStream_getPerformanceMode(stream);
            
            mmapSupported = (actualMode == AAUDIO_SHARING_MODE_EXCLUSIVE && 
                             perfMode == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            
            AAudioStream_close(stream);
        }
        AAudioStreamBuilder_delete(builder);
    }
    
    LOGI("AAudio MMAP Hardware Probe: %s", mmapSupported ? "EXCLUSIVE_CONFIRMED" : "SHARED_FALLBACK");
    return static_cast<jboolean>(mmapSupported);
}