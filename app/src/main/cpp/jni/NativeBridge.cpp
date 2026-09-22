#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <string>
#include <string_view>
#include <vector>

#include <android/log.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>

#include "audio/AAudioEngine.h"
#include "audio/NativeLogQueue.h"

#define LOG_TAG "NativeCoreBridge"

#undef LOGI
#undef LOGE

#define LOGI(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", _buf); \
    client::logging::NativeLogQueue::getInstance().push(4, LOG_TAG, _buf); \
} while (0)

#define LOGE(...) do { \
    char _buf[256]; \
    snprintf(_buf, sizeof(_buf), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", _buf); \
    client::logging::NativeLogQueue::getInstance().push(6, LOG_TAG, _buf); \
} while (0)

#ifndef TCP_NOTSENT_LOWAT
#define TCP_NOTSENT_LOWAT 25
#endif

using namespace client::audio;

namespace {

constexpr char16_t UTF16_REPLACEMENT = 0xFFFD;

/*
 * Decode strict UTF-8 into UTF-16.
 *
 * NewStringUTF() is deliberately not used here:
 * it expects JNI Modified UTF-8, while the native log strings are ordinary
 * UTF-8 byte strings. Invalid UTF-8 is replaced with U+FFFD rather than being
 * passed through as an unsafe JNI string.
 */
std::u16string decodeUtf8ToUtf16(
    std::string_view input) {

    std::u16string output;
    output.reserve(input.size());

    const auto appendReplacement =
        [&output]() {
            output.push_back(
                UTF16_REPLACEMENT
            );
        };

    const auto isContinuation =
        [&input](size_t index) -> bool {
            if (index >= input.size()) {
                return false;
            }

            const unsigned char c =
                static_cast<unsigned char>(
                    input[index]
                );

            return
                c >= 0x80u &&
                c <= 0xBFu;
        };

    size_t i = 0;

    while (i < input.size()) {
        const unsigned char b0 =
            static_cast<unsigned char>(
                input[i]
            );

        if (b0 <= 0x7Fu) {
            output.push_back(
                static_cast<char16_t>(b0)
            );
            ++i;
            continue;
        }

        /*
         * Two-byte sequence:
         * U+0080 .. U+07FF
         */
        if (
            b0 >= 0xC2u &&
            b0 <= 0xDFu
        ) {
            if (
                isContinuation(i + 1)
            ) {
                const unsigned char b1 =
                    static_cast<unsigned char>(
                        input[i + 1]
                    );

                const uint32_t codePoint =
                    (
                        static_cast<uint32_t>(
                            b0 & 0x1Fu
                        ) << 6
                    ) |
                    static_cast<uint32_t>(
                        b1 & 0x3Fu
                    );

                output.push_back(
                    static_cast<char16_t>(
                        codePoint
                    )
                );

                i += 2;
            } else {
                appendReplacement();
                ++i;
            }

            continue;
        }

        /*
         * Three-byte sequence.
         *
         * Explicit second-byte constraints reject:
         *   E0 80..9F  -> overlong
         *   ED A0..BF  -> UTF-16 surrogate range
         */
        if (
            b0 >= 0xE0u &&
            b0 <= 0xEFu
        ) {
            if (
                i + 2 < input.size() &&
                isContinuation(i + 2)
            ) {
                const unsigned char b1 =
                    static_cast<unsigned char>(
                        input[i + 1]
                    );

                const unsigned char b2 =
                    static_cast<unsigned char>(
                        input[i + 2]
                    );

                const bool validSecondByte =
                    (b0 == 0xE0u)
                        ? (b1 >= 0xA0u && b1 <= 0xBFu)
                        : (b0 == 0xEDu)
                            ? (b1 >= 0x80u && b1 <= 0x9Fu)
                            : (b1 >= 0x80u && b1 <= 0xBFu);

                if (validSecondByte) {
                    const uint32_t codePoint =
                        (
                            static_cast<uint32_t>(
                                b0 & 0x0Fu
                            ) << 12
                        ) |
                        (
                            static_cast<uint32_t>(
                                b1 & 0x3Fu
                            ) << 6
                        ) |
                        static_cast<uint32_t>(
                            b2 & 0x3Fu
                        );

                    output.push_back(
                        static_cast<char16_t>(
                            codePoint
                        )
                    );

                    i += 3;
                    continue;
                }
            }

            appendReplacement();
            ++i;
            continue;
        }

        /*
         * Four-byte sequence:
         *
         * F0 90..BF ... -> U+10000+
         * F1..F3        -> normal supplementary planes
         * F4 80..8F ... -> <= U+10FFFF
         */
        if (
            b0 >= 0xF0u &&
            b0 <= 0xF4u
        ) {
            if (
                i + 3 < input.size() &&
                isContinuation(i + 2) &&
                isContinuation(i + 3)
            ) {
                const unsigned char b1 =
                    static_cast<unsigned char>(
                        input[i + 1]
                    );

                const unsigned char b2 =
                    static_cast<unsigned char>(
                        input[i + 2]
                    );

                const unsigned char b3 =
                    static_cast<unsigned char>(
                        input[i + 3]
                    );

                const bool validSecondByte =
                    (b0 == 0xF0u)
                        ? (b1 >= 0x90u && b1 <= 0xBFu)
                        : (b0 == 0xF4u)
                            ? (b1 >= 0x80u && b1 <= 0x8Fu)
                            : (b1 >= 0x80u && b1 <= 0xBFu);

                if (validSecondByte) {
                    const uint32_t codePoint =
                        (
                            static_cast<uint32_t>(
                                b0 & 0x07u
                            ) << 18
                        ) |
                        (
                            static_cast<uint32_t>(
                                b1 & 0x3Fu
                            ) << 12
                        ) |
                        (
                            static_cast<uint32_t>(
                                b2 & 0x3Fu
                            ) << 6
                        ) |
                        static_cast<uint32_t>(
                            b3 & 0x3Fu
                        );

                    const uint32_t adjusted =
                        codePoint - 0x10000u;

                    output.push_back(
                        static_cast<char16_t>(
                            0xD800u +
                            (adjusted >> 10)
                        )
                    );

                    output.push_back(
                        static_cast<char16_t>(
                            0xDC00u +
                            (adjusted & 0x3FFu)
                        )
                    );

                    i += 4;
                    continue;
                }
            }

            appendReplacement();
            ++i;
            continue;
        }

        /*
         * Continuation bytes, C0/C1 overlong starters, and F5..FF are
         * invalid as UTF-8 leading bytes.
         */
        appendReplacement();
        ++i;
    }

    return output;
}

jstring newJStringFromUtf8(
    JNIEnv* env,
    std::string_view input) {

    if (env == nullptr) {
        return nullptr;
    }

    const std::u16string utf16 =
        decodeUtf8ToUtf16(input);

    if (
        utf16.size() >
        static_cast<size_t>(
            std::numeric_limits<jsize>::max()
        )
    ) {
        return nullptr;
    }

    return env->NewString(
        reinterpret_cast<const jchar*>(
            utf16.data()
        ),
        static_cast<jsize>(
            utf16.size()
        )
    );
}

void appendAscii(
    std::u16string& output,
    std::string_view ascii) {

    for (const unsigned char c : ascii) {
        output.push_back(
            static_cast<char16_t>(c)
        );
    }
}

std::u16string getStaticStringField(
    JNIEnv* env,
    jclass clazz,
    const char* fieldName) {

    if (
        env == nullptr ||
        clazz == nullptr ||
        fieldName == nullptr
    ) {
        return {};
    }

    jfieldID field =
        env->GetStaticFieldID(
            clazz,
            fieldName,
            "Ljava/lang/String;"
        );

    if (
        field == nullptr ||
        env->ExceptionCheck()
    ) {
        env->ExceptionClear();
        return {};
    }

    auto value =
        static_cast<jstring>(
            env->GetStaticObjectField(
                clazz,
                field
            )
        );

    if (
        value == nullptr ||
        env->ExceptionCheck()
    ) {
        env->ExceptionClear();

        if (value != nullptr) {
            env->DeleteLocalRef(value);
        }

        return {};
    }

    const jsize length =
        env->GetStringLength(value);

    if (length <= 0) {
        env->DeleteLocalRef(value);
        return {};
    }

    std::u16string result(
        static_cast<size_t>(length),
        u'\0'
    );

    env->GetStringRegion(
        value,
        0,
        length,
        reinterpret_cast<jchar*>(
            result.data()
        )
    );

    env->DeleteLocalRef(value);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return {};
    }

    return result;
}

int getAndroidApiLevel(
    JNIEnv* env) {

    if (env == nullptr) {
        return 0;
    }

    jclass versionClass =
        env->FindClass(
            "android/os/Build$VERSION"
        );

    if (
        versionClass == nullptr ||
        env->ExceptionCheck()
    ) {
        env->ExceptionClear();

        if (versionClass != nullptr) {
            env->DeleteLocalRef(versionClass);
        }

        return 0;
    }

    jfieldID sdkField =
        env->GetStaticFieldID(
            versionClass,
            "SDK_INT",
            "I"
        );

    if (
        sdkField == nullptr ||
        env->ExceptionCheck()
    ) {
        env->ExceptionClear();
        env->DeleteLocalRef(versionClass);
        return 0;
    }

    const jint apiLevel =
        env->GetStaticIntField(
            versionClass,
            sdkField
        );

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(versionClass);
        return 0;
    }

    env->DeleteLocalRef(versionClass);

    return static_cast<int>(
        apiLevel
    );
}

std::u16string getRuntimeHardwareDescription(
    JNIEnv* env) {

    std::u16string result;
    result.append(u"Android audio hardware");

    jclass buildClass =
        env->FindClass(
            "android/os/Build"
        );

    if (
        buildClass == nullptr ||
        env->ExceptionCheck()
    ) {
        env->ExceptionClear();

        if (buildClass != nullptr) {
            env->DeleteLocalRef(buildClass);
        }

        result.append(
            u" [SoC: unavailable]"
        );

        return result;
    }

    const std::u16string deviceModel =
        getStaticStringField(
            env,
            buildClass,
            "MODEL"
        );

    const int apiLevel =
        getAndroidApiLevel(env);

    /*
     * Build.SOC_MANUFACTURER and Build.SOC_MODEL are public Android 12+
     * properties. On older API levels there is no equivalent public SDK
     * field, so the diagnostic explicitly reports the information as
     * unavailable instead of inventing a chipset.
     */
    std::u16string socManufacturer;
    std::u16string socModel;

    if (apiLevel >= 31) {
        socManufacturer =
            getStaticStringField(
                env,
                buildClass,
                "SOC_MANUFACTURER"
            );

        socModel =
            getStaticStringField(
                env,
                buildClass,
                "SOC_MODEL"
            );
    }

    if (
        !socManufacturer.empty() ||
        !socModel.empty()
    ) {
        result.append(
            u" [SoC:"
        );

        if (!socManufacturer.empty()) {
            result.push_back(u' ');
            result.append(socManufacturer);
        }

        if (!socModel.empty()) {
            if (!socManufacturer.empty()) {
                result.push_back(u' ');
            } else {
                result.push_back(u' ');
            }

            result.append(socModel);
        }

        result.push_back(u']');
    } else {
        result.append(
            u" [SoC: unavailable]"
        );
    }

    if (!deviceModel.empty()) {
        result.append(
            u" [Device:"
        );
        result.push_back(u' ');
        result.append(deviceModel);
        result.push_back(u']');
    }

    env->DeleteLocalRef(buildClass);

    return result;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_client_app_audio_NativeAudioBridge_getHardwareCoreInfo(
    JNIEnv* env,
    jobject /* this */) {

    auto& engine =
        AAudioEngine::getInstance();

    const bool isMmap =
        engine.isMmapActive();

    const bool isExclusive =
        engine.isExclusiveSharingActive();

    const int32_t inRate =
        engine.getActualCaptureSampleRate();

    const int32_t outRate =
        engine.getActualPlaybackSampleRate();

    const int32_t inDevId =
        engine.getActiveInputDeviceId();

    const int32_t outDevId =
        engine.getActiveOutputDeviceId();

    std::u16string info =
        getRuntimeHardwareDescription(env);

    info.append(u" [In:");
    appendAscii(
        info,
        std::to_string(inRate)
    );
    info.append(u"Hz/ID:");
    appendAscii(
        info,
        std::to_string(inDevId)
    );

    info.append(u" -> Out:");
    appendAscii(
        info,
        std::to_string(outRate)
    );
    info.append(u"Hz/ID:");
    appendAscii(
        info,
        std::to_string(outDevId)
    );

    info.append(u", Exclusive:");
    info.append(
        isExclusive
            ? u"yes"
            : u"no"
    );

    info.append(u", MMAP:");
    info.append(
        isMmap
            ? u"yes"
            : u"no"
    );

    if (
        info.size() >
        static_cast<size_t>(
            std::numeric_limits<jsize>::max()
        )
    ) {
        return nullptr;
    }

    return env->NewString(
        reinterpret_cast<const jchar*>(
            info.data()
        ),
        static_cast<jsize>(
            info.size()
        )
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_initAudioRoute(
    JNIEnv* /* env */,
    jobject /* this */,
    jboolean isBluetooth,
    jint sampleRate,
    jint inputDeviceId,
    jint outputDeviceId) {

    LOGI(
        "initAudioRoute called: isBluetooth=%d, sampleRate=%d, inDevId=%d, outDevId=%d",
        static_cast<int>(isBluetooth),
        static_cast<int>(sampleRate),
        static_cast<int>(inputDeviceId),
        static_cast<int>(outputDeviceId)
    );

    return static_cast<jboolean>(
        AAudioEngine::getInstance().init(
            isBluetooth,
            sampleRate,
            inputDeviceId,
            outputDeviceId
        )
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_startAudio(
    JNIEnv* /* env */,
    jobject /* this */) {

    LOGI("startAudio called");

    return static_cast<jboolean>(
        AAudioEngine::getInstance().start()
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_startPlaybackAudio(
    JNIEnv* /* env */,
    jobject /* this */) {

    LOGI("startPlaybackAudio called");

    return static_cast<jboolean>(
        AAudioEngine::getInstance().startPlayback()
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_startCaptureAudio(
    JNIEnv* /* env */,
    jobject /* this */) {

    LOGI("startCaptureAudio called");

    return static_cast<jboolean>(
        AAudioEngine::getInstance().startCapture()
    );
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActualPlaybackSampleRate(
    JNIEnv* /* env */,
    jobject /* this */) {

    return AAudioEngine::getInstance()
        .getActualPlaybackSampleRate();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActualPlaybackChannels(
    JNIEnv* /* env */,
    jobject /* this */) {

    return AAudioEngine::getInstance()
        .getActualPlaybackChannels();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActualPlaybackFormat(
    JNIEnv* /* env */,
    jobject /* this */) {

    return AAudioEngine::getInstance()
        .getActualPlaybackFormat();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActualCaptureSampleRate(
    JNIEnv* /* env */,
    jobject /* this */) {

    return AAudioEngine::getInstance()
        .getActualCaptureSampleRate();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActualCaptureChannels(
    JNIEnv* /* env */,
    jobject /* this */) {

    return AAudioEngine::getInstance()
        .getActualCaptureChannels();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActiveInputDeviceId(
    JNIEnv* /* env */,
    jobject /* this */) {

    return AAudioEngine::getInstance()
        .getActiveInputDeviceId();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_getActiveOutputDeviceId(
    JNIEnv* /* env */,
    jobject /* this */) {

    return AAudioEngine::getInstance()
        .getActiveOutputDeviceId();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_client_app_audio_NativeAudioBridge_getPendingPlaybackFrames(
    JNIEnv* /* env */,
    jobject /* this */) {

    return static_cast<jlong>(
        AAudioEngine::getInstance()
            .getPendingPlaybackFrames()
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_isMmapActive(
    JNIEnv* /* env */,
    jobject /* this */) {

    return static_cast<jboolean>(
        AAudioEngine::getInstance()
            .isMmapActive()
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_isExclusiveSharingActive(
    JNIEnv* /* env */,
    jobject /* this */) {

    return static_cast<jboolean>(
        AAudioEngine::getInstance()
            .isExclusiveSharingActive()
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_stopCaptureAudio(
    JNIEnv* /* env */,
    jobject /* this */) {

    LOGI("stopCaptureAudio called");

    AAudioEngine::getInstance()
        .stopCapture();
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_stopAudio(
    JNIEnv* /* env */,
    jobject /* this */) {

    LOGI("stopAudio called");

    AAudioEngine::getInstance()
        .stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_client_app_audio_NativeAudioBridge_isAudioDisconnected(
    JNIEnv* /* env */,
    jobject /* this */) {

    return static_cast<jboolean>(
        AAudioEngine::getInstance()
            .isDisconnected()
    );
}

// AUD-005.6: jlong generation обязателен + проверка чётности байт PCM16
extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_writePlaybackByteArray(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray byteArray,
    jint offset,
    jint length,
    jlong generation) {

    if (
        !byteArray ||
        offset < 0 ||
        length <= 0
    ) {
        return 0;
    }

    if ((length & 1) != 0) {
        return 0;
    }

    const jsize arrayLen =
        env->GetArrayLength(byteArray);

    if (
        offset > arrayLen ||
        length > arrayLen - offset
    ) {
        return 0;
    }

    const size_t frames =
        static_cast<size_t>(length) /
        sizeof(int16_t);

    thread_local std::vector<int16_t>
        playbackJniBuffer;

    if (
        playbackJniBuffer.size() <
        frames
    ) {
        playbackJniBuffer.resize(
            frames * 2
        );
    }

    env->GetByteArrayRegion(
        byteArray,
        offset,
        length,
        reinterpret_cast<jbyte*>(
            playbackJniBuffer.data()
        )
    );

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return 0;
    }

    const size_t writtenFrames =
        AAudioEngine::getInstance()
            .writePlaybackPcm(
                playbackJniBuffer.data(),
                frames,
                static_cast<uint64_t>(
                    generation
                )
            );

    return static_cast<jint>(
        writtenFrames *
        sizeof(int16_t)
    );
}

// AUD-005.6: jlong generation обязателен + проверка чётности байт PCM16
extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_writePlaybackDirect(
    JNIEnv* env,
    jobject /* this */,
    jobject byteBuffer,
    jint offsetBytes,
    jint lengthBytes,
    jlong generation) {

    if (
        !byteBuffer ||
        offsetBytes < 0 ||
        lengthBytes <= 0
    ) {
        return 0;
    }

    if ((lengthBytes & 1) != 0) {
        return 0;
    }

    if ((offsetBytes & 1) != 0) {
        return 0;
    }

    const jlong capacity =
        env->GetDirectBufferCapacity(
            byteBuffer
        );

    if (
        capacity < 0 ||
        static_cast<jlong>(offsetBytes) >
            capacity ||
        static_cast<jlong>(lengthBytes) >
            capacity -
                static_cast<jlong>(offsetBytes)
    ) {
        LOGE(
            "writePlaybackDirect OOB: Cap=%lld, Offset=%d, Length=%d",
            static_cast<long long>(capacity),
            static_cast<int>(offsetBytes),
            static_cast<int>(lengthBytes)
        );
        return 0;
    }

    auto* bufferPtr =
        static_cast<int16_t*>(
            env->GetDirectBufferAddress(
                byteBuffer
            )
        );

    if (!bufferPtr) {
        return 0;
    }

    auto* startPtr =
        reinterpret_cast<int16_t*>(
            reinterpret_cast<char*>(
                bufferPtr
            ) +
            offsetBytes
        );

    const size_t frames =
        static_cast<size_t>(lengthBytes) /
        sizeof(int16_t);

    const size_t writtenFrames =
        AAudioEngine::getInstance()
            .writePlaybackPcm(
                startPtr,
                frames,
                static_cast<uint64_t>(
                    generation
                )
            );

    return static_cast<jint>(
        writtenFrames *
        sizeof(int16_t)
    );
}

extern "C" JNIEXPORT jint JNICALL
Java_com_client_app_audio_NativeAudioBridge_readCaptureDirect(
    JNIEnv* env,
    jobject /* this */,
    jobject byteBuffer,
    jint capacityBytes) {

    if (
        !byteBuffer ||
        capacityBytes <= 0
    ) {
        return 0;
    }

    const jlong realCap =
        env->GetDirectBufferCapacity(
            byteBuffer
        );

    if (realCap < 0) {
        return 0;
    }

    const jint safeCapacity =
        static_cast<jint>(
            std::min<jlong>(
                realCap,
                static_cast<jlong>(
                    capacityBytes
                )
            )
        );

    if (safeCapacity <= 0) {
        return 0;
    }

    auto* bufferPtr =
        static_cast<int16_t*>(
            env->GetDirectBufferAddress(
                byteBuffer
            )
        );

    if (!bufferPtr) {
        return 0;
    }

    const size_t maxFrames =
        static_cast<size_t>(
            safeCapacity
        ) /
        sizeof(int16_t);

    const size_t readFrames =
        AAudioEngine::getInstance()
            .readCapturePcm(
                bufferPtr,
                maxFrames
            );

    return static_cast<jint>(
        readFrames *
        sizeof(int16_t)
    );
}

// AUD-005.6: jlong generation обязателен
extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_flushPlayback(
    JNIEnv* /* env */,
    jobject /* this */,
    jlong generation) {

    AAudioEngine::getInstance()
        .flushPlayback(
            static_cast<uint64_t>(
                generation
            )
        );
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_triggerBargeInEarcon(
    JNIEnv* /* env */,
    jobject /* this */) {

    AAudioEngine::getInstance()
        .triggerBargeInEarcon();
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_tuneNativeSocket(
    JNIEnv* /* env */,
    jobject /* this */,
    jint fd) {

    if (fd <= 0) {
        return;
    }

    int flag = 1;

    const int res1 =
        setsockopt(
            fd,
            IPPROTO_TCP,
            TCP_NODELAY,
            &flag,
            sizeof(flag)
        );

    int lowat = 16384;

    const int res2 =
        setsockopt(
            fd,
            IPPROTO_TCP,
            TCP_NOTSENT_LOWAT,
            &lowat,
            sizeof(lowat)
        );

    LOGI(
        "tuneNativeSocket applied for fd=%d: TCP_NODELAY res=%d, TCP_NOTSENT_LOWAT res=%d",
        fd,
        res1,
        res2
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_client_app_audio_NativeAudioBridge_getSpectrumData(
    JNIEnv* env,
    jobject /* this */,
    jfloatArray outArray) {

    if (!outArray) {
        return;
    }

    const jsize len =
        env->GetArrayLength(
            outArray
        );

    if (len < 7) {
        return;
    }

    client::dsp::SpectrumSnapshot
        snapshot;

    AAudioEngine::getInstance()
        .getSpectrumData(
            snapshot
        );

    jfloat data[7];

    for (int i = 0; i < 5; ++i) {
        data[i] =
            snapshot.bands[i];
    }

    data[5] =
        snapshot.micRms;

    data[6] =
        snapshot.outRms;

    env->SetFloatArrayRegion(
        outArray,
        0,
        7,
        data
    );
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_client_app_audio_NativeAudioBridge_drainNativeLogs(
    JNIEnv* env,
    jobject /* this */) {

    std::vector<
        client::logging::NativeLogItem
    > drained;

    drained.reserve(64);

    client::logging::NativeLogItem item;

    while (
        drained.size() < 64 &&
        client::logging::NativeLogQueue
            ::getInstance()
            .pop(item)
    ) {
        drained.push_back(item);
    }

    if (drained.empty()) {
        return nullptr;
    }

    jclass stringClass =
        env->FindClass(
            "java/lang/String"
        );

    if (
        stringClass == nullptr ||
        env->ExceptionCheck()
    ) {
        env->ExceptionClear();

        if (stringClass != nullptr) {
            env->DeleteLocalRef(
                stringClass
            );
        }

        return nullptr;
    }

    const size_t totalElementsSize =
        drained.size() * 3u;

    if (
        totalElementsSize >
        static_cast<size_t>(
            std::numeric_limits<jsize>::max()
        )
    ) {
        env->DeleteLocalRef(
            stringClass
        );
        return nullptr;
    }

    const jsize totalElements =
        static_cast<jsize>(
            totalElementsSize
        );

    jobjectArray resultArray =
        env->NewObjectArray(
            totalElements,
            stringClass,
            nullptr
        );

    if (
        resultArray == nullptr ||
        env->ExceptionCheck()
    ) {
        env->ExceptionClear();

        if (resultArray != nullptr) {
            env->DeleteLocalRef(
                resultArray
            );
        }

        env->DeleteLocalRef(
            stringClass
        );

        return nullptr;
    }

    for (size_t i = 0;
         i < drained.size();
         ++i) {

        const std::string level =
            std::to_string(
                drained[i].level
            );

        jstring jLevel =
            newJStringFromUtf8(
                env,
                level
            );

        jstring jTag =
            newJStringFromUtf8(
                env,
                drained[i].tag
                    ? std::string_view(
                        drained[i].tag
                    )
                    : std::string_view()
            );

        jstring jMsg =
            newJStringFromUtf8(
                env,
                drained[i].message
                    ? std::string_view(
                        drained[i].message
                    )
                    : std::string_view()
            );

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }

        const jsize baseIdx =
            static_cast<jsize>(
                i * 3u
            );

        env->SetObjectArrayElement(
            resultArray,
            baseIdx,
            jLevel
        );

        env->SetObjectArrayElement(
            resultArray,
            baseIdx + 1,
            jTag
        );

        env->SetObjectArrayElement(
            resultArray,
            baseIdx + 2,
            jMsg
        );

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            break;
        }

        if (jLevel != nullptr) {
            env->DeleteLocalRef(
                jLevel
            );
        }

        if (jTag != nullptr) {
            env->DeleteLocalRef(
                jTag
            );
        }

        if (jMsg != nullptr) {
            env->DeleteLocalRef(
                jMsg
            );
        }
    }

    env->DeleteLocalRef(
        stringClass
    );

    return resultArray;
}