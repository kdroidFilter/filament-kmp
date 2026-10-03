// Hand-written half of the JNI layer: what the generated forwarders can't express.
// Backs io.github.erkko68.filament.jni.FilaJni.

#include <jni.h>

#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <exception>

#include <utils/Panic.h>

#ifdef __ANDROID__
#include <android/set_abort_message.h>

// Private upstream header (backend/include/private/backend/VirtualMachineEnv.h); only this entry is needed.
namespace filament {
class VirtualMachineEnv {
public:
    static jint JNI_OnLoad(JavaVM* vm);
};
} // namespace filament
#endif

// A panic is API misuse to fix, not an error to catch: log why, then abort, on any thread. Filament built with
// exceptions (as here) leaves logging to the catcher, so an uncaught panic used to abort with no reason.
static void abortOnPanic(void*, utils::Panic const& panic) {
    panic.log();
#ifdef __ANDROID__
    android_set_abort_message(panic.what()); // the tombstone's "Abort message", seen in crash reports
#endif
    std::abort();
}

static JavaVM* sVm = nullptr;
static jmethodID sInvoke = nullptr; // FilaCallback.invoke(long, long)

// A Filament panic thrown under a JNI method can't be caught on the way out (the forwarders are C), so the
// process terminates. Say what failed and from where first: the panic's message (function, line, reason)
// and the Java stack of the thread that called into Filament.
[[noreturn]] static void reportUncaughtException() {
    if (std::exception_ptr current = std::current_exception()) {
        try {
            std::rethrow_exception(current);
        } catch (const std::exception& e) {
            std::fprintf(stderr, "filament-kmp: uncaught native exception: %s\n", e.what());
        } catch (...) {
            std::fprintf(stderr, "filament-kmp: uncaught native exception of unknown type\n");
        }
    }
    JNIEnv* env;
    if (sVm && sVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (jclass thread = env->FindClass("java/lang/Thread")) {
            if (jmethodID dumpStack = env->GetStaticMethodID(thread, "dumpStack", "()V")) {
                env->CallStaticVoidMethod(thread, dumpStack);
            }
        }
    }
    std::fflush(stderr);
    std::abort();
}

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return -1;
    sVm = vm;
    utils::Panic::setPanicHandler(abortOnPanic, nullptr);
    std::set_terminate(reportUncaughtException); // other uncaught exceptions
#ifdef __ANDROID__
    // Filament's Android backend (streams, EGL helpers) needs the VM, as in upstream filament-android.
    filament::VirtualMachineEnv::JNI_OnLoad(vm);
#endif
    jclass callback = env->FindClass("io/github/erkko68/filament/jni/FilaCallback");
    sInvoke = env->GetMethodID(callback, "invoke", "(JJ)V");
    env->DeleteLocalRef(callback);
    return JNI_VERSION_1_6;
}

// Callbacks fire on Filament's driver thread; attach it once, as a daemon so it never blocks VM exit.
static JNIEnv* attachedEnv() {
    JNIEnv* env;
    if (sVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
#ifdef __ANDROID__
        sVm->AttachCurrentThreadAsDaemon(&env, nullptr);
#else
        sVm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr); // desktop jni.h takes void**
#endif
    }
    return env;
}

// The userData behind every trampoline (FilaJni.newCallback).
struct Callback {
    jobject target; // global ref to a FilaCallback
    bool once;
};

static void release(JNIEnv* env, Callback* callback) {
    env->DeleteGlobalRef(callback->target);
    delete callback;
}

static void dispatch(void* userData, jlong a, jlong b) {
    JNIEnv* env = attachedEnv();
    auto callback = static_cast<Callback*>(userData);
    env->CallVoidMethod(callback->target, sInvoke, a, b);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
    if (callback->once) release(env, callback);
}

// One trampoline per C callback shape, matching web's Callbacks (userOnly, argUser, userStatus, argUserStatus, keepBuffer, freeBuffer).
static void userOnly(void* userData) { dispatch(userData, 0, 0); }
static void argUser(void* arg, void* userData) { dispatch(userData, reinterpret_cast<jlong>(arg), 0); }
// Async completions: the status (AsyncCallStatus) arrives as `b`.
static void userStatus(void* userData, int status) { dispatch(userData, 0, status); }
static void argUserStatus(void* arg, void* userData, int status) { dispatch(userData, reinterpret_cast<jlong>(arg), status); }
static void keepBuffer(void* buffer, size_t size, void* userData) {
    dispatch(userData, reinterpret_cast<jlong>(buffer), static_cast<jlong>(size));
}
// For uploads copied into native memory: frees the copy once Filament has consumed it.
static void freeBuffer(void* buffer, size_t size, void* userData) {
    std::free(buffer);
    if (userData) dispatch(userData, reinterpret_cast<jlong>(buffer), static_cast<jlong>(size));
}

#define FILA_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_io_github_erkko68_filament_jni_FilaJni_##name

FILA_JNI(jlong, alloc)(JNIEnv*, jclass, jlong size) {
    return reinterpret_cast<jlong>(std::calloc(1, static_cast<size_t>(size)));
}

FILA_JNI(void, free)(JNIEnv*, jclass, jlong ptr) {
    std::free(reinterpret_cast<void*>(ptr));
}

FILA_JNI(jobject, view)(JNIEnv* env, jclass, jlong ptr, jlong size) {
    return env->NewDirectByteBuffer(reinterpret_cast<void*>(ptr), size);
}

FILA_JNI(jlong, newCallback)(JNIEnv* env, jclass, jobject target, jboolean once) {
    return reinterpret_cast<jlong>(new Callback { env->NewGlobalRef(target), once == JNI_TRUE });
}

FILA_JNI(void, releaseCallback)(JNIEnv* env, jclass, jlong userData) {
    release(env, reinterpret_cast<Callback*>(userData));
}

FILA_JNI(jlong, userOnly)(JNIEnv*, jclass) { return reinterpret_cast<jlong>(&userOnly); }
FILA_JNI(jlong, argUser)(JNIEnv*, jclass) { return reinterpret_cast<jlong>(&argUser); }
FILA_JNI(jlong, userStatus)(JNIEnv*, jclass) { return reinterpret_cast<jlong>(&userStatus); }
FILA_JNI(jlong, argUserStatus)(JNIEnv*, jclass) { return reinterpret_cast<jlong>(&argUserStatus); }
FILA_JNI(jlong, keepBuffer)(JNIEnv*, jclass) { return reinterpret_cast<jlong>(&keepBuffer); }
FILA_JNI(jlong, freeBuffer)(JNIEnv*, jclass) { return reinterpret_cast<jlong>(&freeBuffer); }

FILA_JNI(jint, nativePointerSize)(JNIEnv*, jclass) { return sizeof(void*); }

FILA_JNI(jstring, readString)(JNIEnv* env, jclass, jlong ptr) {
    return ptr ? env->NewStringUTF(reinterpret_cast<const char*>(ptr)) : nullptr;
}
