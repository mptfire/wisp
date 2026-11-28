#include <jni.h>

#include <cstdint>
#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <utility>

#include "slimt/Frontend.hh"
#include "slimt/Io.hh"
#include "slimt/Model.hh"
#include "slimt/Response.hh"

using slimt::Async;
using slimt::Config;
using slimt::Model;
using slimt::Options;
using slimt::Package;
using slimt::Response;

namespace {

void throwIllegalState(JNIEnv* env, const char* msg) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg != nullptr ? msg : "slimt native error");
        env->DeleteLocalRef(cls);
    }
}

std::string jstringToStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* raw = env->GetStringUTFChars(s, nullptr);
    if (raw == nullptr) return std::string();
    std::string out(raw);
    env->ReleaseStringUTFChars(s, raw);
    return out;
}

std::string byteArrayToStdString(JNIEnv* env, jbyteArray arr) {
    if (arr == nullptr) return std::string();
    jsize len = env->GetArrayLength(arr);
    if (len <= 0) return std::string();
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

jbyteArray stdStringToByteArray(JNIEnv* env, const std::string& s) {
    jsize len = static_cast<jsize>(s.size());
    jbyteArray arr = env->NewByteArray(len);
    if (arr == nullptr) return nullptr;
    if (len > 0) {
        env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(s.data()));
    }
    return arr;
}

void detectLayerCounts(const std::string& modelPath,
                       size_t& encoderLayers,
                       size_t& decoderLayers) {
    encoderLayers = 0;
    decoderLayers = 0;
    slimt::io::MmapFile mmap(modelPath);
    auto items = slimt::io::load_items(mmap.data());
    for (const auto& item : items) {
        const std::string& name = item.name;
        auto checkPrefix = [&](const char* prefix, size_t& out) {
            const size_t plen = std::strlen(prefix);
            if (name.compare(0, plen, prefix) != 0) return;
            size_t idx = 0;
            size_t pos = plen;
            while (pos < name.size() && name[pos] >= '0' && name[pos] <= '9') {
                idx = idx * 10 + static_cast<size_t>(name[pos] - '0');
                ++pos;
            }
            if (pos > plen && idx > out) out = idx;
        };
        checkPrefix("encoder_l", encoderLayers);
        checkPrefix("decoder_l", decoderLayers);
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_wisp_app_translate_SlimtNative_nativeCreateService(JNIEnv* env, jobject, jint workers) {
    try {
        Config config;
        config.workers = workers > 0 ? static_cast<size_t>(workers) : 1;
        config.cache_size = 512;
        auto* service = new Async(config);
        return reinterpret_cast<jlong>(service);
    } catch (const std::exception& e) {
        throwIllegalState(env, e.what());
        return 0;
    } catch (...) {
        throwIllegalState(env, "Unknown error creating slimt service");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_wisp_app_translate_SlimtNative_nativeDestroyService(JNIEnv*, jobject, jlong servicePtr) {
    if (servicePtr == 0) return;
    delete reinterpret_cast<Async*>(servicePtr);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_wisp_app_translate_SlimtNative_nativeLoadModel(
    JNIEnv* env,
    jobject,
    jstring modelPath,
    jstring vocabPath,
    jstring shortlistPath,
    jstring targetVocabPath) {
    try {
        Package<std::string> package{
            .model = jstringToStdString(env, modelPath),
            .vocabulary = jstringToStdString(env, vocabPath),
            .target_vocabulary = jstringToStdString(env, targetVocabPath),
            .shortlist = jstringToStdString(env, shortlistPath),
            .ssplit = std::string(),
        };

        Model::Config config = slimt::preset::tiny();
        size_t encLayers = 0;
        size_t decLayers = 0;
        detectLayerCounts(package.model, encLayers, decLayers);
        if (encLayers > 0) config.encoder_layers = encLayers;
        if (decLayers > 0) config.decoder_layers = decLayers;

        auto* holder = new std::shared_ptr<Model>(std::make_shared<Model>(config, package));
        return reinterpret_cast<jlong>(holder);
    } catch (const std::exception& e) {
        throwIllegalState(env, e.what());
        return 0;
    } catch (...) {
        throwIllegalState(env, "Unknown error loading slimt model");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_wisp_app_translate_SlimtNative_nativeDestroyModel(JNIEnv*, jobject, jlong modelPtr) {
    if (modelPtr == 0) return;
    delete reinterpret_cast<std::shared_ptr<Model>*>(modelPtr);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wisp_app_translate_SlimtNative_nativeTranslate(
    JNIEnv* env,
    jobject,
    jlong servicePtr,
    jlong modelPtr,
    jbyteArray textUtf8) {
    if (servicePtr == 0 || modelPtr == 0) {
        throwIllegalState(env, "Native translation service or model is closed");
        return nullptr;
    }
    try {
        auto* service = reinterpret_cast<Async*>(servicePtr);
        auto& model = *reinterpret_cast<std::shared_ptr<Model>*>(modelPtr);
        std::string source = byteArrayToStdString(env, textUtf8);
        Options options;
        Response resp = service->translate(model, std::move(source), options).future().get();
        return stdStringToByteArray(env, resp.target.text);
    } catch (const std::exception& e) {
        throwIllegalState(env, e.what());
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "Unknown error running native translation");
        return nullptr;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_wisp_app_translate_SlimtNative_nativePivotTranslate(
    JNIEnv* env,
    jobject,
    jlong servicePtr,
    jlong firstModelPtr,
    jlong secondModelPtr,
    jbyteArray textUtf8) {
    if (servicePtr == 0 || firstModelPtr == 0 || secondModelPtr == 0) {
        throwIllegalState(env, "Native translation service or model is closed");
        return nullptr;
    }
    try {
        auto* service = reinterpret_cast<Async*>(servicePtr);
        auto& first = *reinterpret_cast<std::shared_ptr<Model>*>(firstModelPtr);
        auto& second = *reinterpret_cast<std::shared_ptr<Model>*>(secondModelPtr);
        std::string source = byteArrayToStdString(env, textUtf8);
        Options options;
        Response resp = service->pivot(first, second, std::move(source), options).future().get();
        return stdStringToByteArray(env, resp.target.text);
    } catch (const std::exception& e) {
        throwIllegalState(env, e.what());
        return nullptr;
    } catch (...) {
        throwIllegalState(env, "Unknown error running native pivot translation");
        return nullptr;
    }
}
