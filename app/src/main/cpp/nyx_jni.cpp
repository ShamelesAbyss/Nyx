#include "nyx_session.h"
#include <jni.h>
#include <mutex>
#include <stdexcept>

namespace {
nyx::Session session;
std::mutex operation_mutex;
struct JavaException {};
void throw_java(JNIEnv * env, const char * cls, const char * message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass(cls);
    if (type) { env->ThrowNew(type, message); env->DeleteLocalRef(type); }
}
void translate(JNIEnv * env) {
    try { throw; }
    catch (const JavaException &) { /* callback exception remains pending */ }
    catch (const nyx::Cancelled &) { throw_java(env, "java/util/concurrent/CancellationException", "Native inference cancelled"); }
    catch (const std::bad_alloc &) { throw_java(env, "java/io/IOException", "Native memory allocation failed"); }
    catch (const std::exception & e) { throw_java(env, "java/io/IOException", e.what()); }
    catch (...) { throw_java(env, "java/io/IOException", "Unknown native inference failure"); }
}
std::string bytes(JNIEnv * env, jbyteArray input, size_t limit) {
    if (!input) throw std::invalid_argument("Null JNI input");
    const jsize count = env->GetArrayLength(input);
    if (static_cast<size_t>(count) > limit) throw std::invalid_argument("JNI input exceeds safe size");
    std::string out(static_cast<size_t>(count), '\0');
    if (count) env->GetByteArrayRegion(input, 0, count, reinterpret_cast<jbyte *>(out.data()));
    if (env->ExceptionCheck()) throw JavaException();
    return out;
}
}
extern "C" JNIEXPORT void JNICALL
Java_com_nyx_ai_nativeinference_NativeBindings_beginOperation(JNIEnv *, jobject) { session.begin(); }
extern "C" JNIEXPORT void JNICALL
Java_com_nyx_ai_nativeinference_NativeBindings_cancel(JNIEnv *, jobject) { session.cancel(); }
extern "C" JNIEXPORT jint JNICALL
Java_com_nyx_ai_nativeinference_NativeBindings_load(JNIEnv * env, jobject, jbyteArray path, jint context, jint threads) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try { return session.load(bytes(env, path, 4096), context, threads); }
    catch (...) { translate(env); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_nyx_ai_nativeinference_NativeBindings_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    session.unload();
}
extern "C" JNIEXPORT jint JNICALL
Java_com_nyx_ai_nativeinference_NativeBindings_generate(JNIEnv * env, jobject, jobjectArray roles,
        jobjectArray contents, jfloat temperature, jint max_tokens, jint seed, jobject sink) {
    std::lock_guard<std::mutex> lock(operation_mutex);
    try {
        if (!roles || !contents || !sink) throw std::invalid_argument("Null generation arguments");
        const jsize count = env->GetArrayLength(roles);
        if (count < 1 || count > 256 || count != env->GetArrayLength(contents))
            throw std::invalid_argument("Invalid message count");
        std::vector<std::pair<std::string, std::string>> messages;
        size_t total = 0;
        for (jsize i = 0; i < count; ++i) {
            auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i));
            auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
            if (env->ExceptionCheck()) throw JavaException();
            auto r = bytes(env, role, 16);
            auto c = bytes(env, content, 1024 * 1024);
            env->DeleteLocalRef(role); env->DeleteLocalRef(content);
            total += r.size() + c.size();
            if (total > 1024 * 1024) throw std::invalid_argument("Messages exceed 1 MiB");
            messages.emplace_back(std::move(r), std::move(c));
        }
        jclass sink_class = env->GetObjectClass(sink);
        if (!sink_class) throw JavaException();
        jmethodID on_bytes = env->GetMethodID(sink_class, "onBytes", "([B)V");
        env->DeleteLocalRef(sink_class);
        if (!on_bytes) throw JavaException();
        return session.generate(messages, temperature, max_tokens, static_cast<uint32_t>(seed),
            [&](const std::string & piece) {
                jbyteArray data = env->NewByteArray(static_cast<jsize>(piece.size()));
                if (!data) throw JavaException();
                env->SetByteArrayRegion(data, 0, static_cast<jsize>(piece.size()), reinterpret_cast<const jbyte *>(piece.data()));
                if (!env->ExceptionCheck()) env->CallVoidMethod(sink, on_bytes, data);
                env->DeleteLocalRef(data);
                if (env->ExceptionCheck()) throw JavaException();
            });
    } catch (...) { translate(env); return 0; }
}
