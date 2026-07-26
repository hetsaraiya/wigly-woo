// woo_jni.c — the Android side of the FFI boundary.
//
// It does two jobs: (1) map Kotlin `external` methods on CoreBridge to the Go
// core's C ABI, and (2) register a C event callback that marshals each event
// up to CoreBridge.onEvent(String) via JNI. This is the Android equivalent of
// the macOS @convention(c) callback — same contract, different plumbing.

#include <jni.h>
#include "woocore.h"

static JavaVM*   g_vm       = NULL;
static jclass    g_bridge   = NULL; // global ref to com.wiglywoo.CoreBridge
static jmethodID g_on_event = NULL; // static onEvent(String)

// Called by the Go core on its own thread; attach to the JVM and forward.
static void event_cb(const char* json) {
    JNIEnv* env = NULL;
    int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != 0) return;
        attached = 1;
    }
    jstring s = (*env)->NewStringUTF(env, json);
    (*env)->CallStaticVoidMethod(env, g_bridge, g_on_event, s);
    (*env)->DeleteLocalRef(env, s);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_vm = vm;
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;

    jclass cls = (*env)->FindClass(env, "com/wiglywoo/CoreBridge");
    g_bridge = (jclass)(*env)->NewGlobalRef(env, cls);
    g_on_event = (*env)->GetStaticMethodID(env, g_bridge, "onEvent", "(Ljava/lang/String;)V");

    woo_set_event_cb(event_cb);
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_wiglywoo_CoreBridge_nativeStart(JNIEnv* env, jobject thiz, jstring config) {
    const char* c = (*env)->GetStringUTFChars(env, config, NULL);
    int rc = woo_start(c);
    (*env)->ReleaseStringUTFChars(env, config, c);
    return rc;
}

JNIEXPORT void JNICALL
Java_com_wiglywoo_CoreBridge_nativeStop(JNIEnv* env, jobject thiz) {
    woo_stop();
}

JNIEXPORT jstring JNICALL
Java_com_wiglywoo_CoreBridge_nativeIdentity(JNIEnv* env, jobject thiz) {
    char* j = woo_identity_json();
    jstring s = (*env)->NewStringUTF(env, j);
    woo_free(j);
    return s;
}

JNIEXPORT jstring JNICALL
Java_com_wiglywoo_CoreBridge_nativePeers(JNIEnv* env, jobject thiz) {
    char* j = woo_peers_json();
    jstring s = (*env)->NewStringUTF(env, j);
    woo_free(j);
    return s;
}

JNIEXPORT jint JNICALL
Java_com_wiglywoo_CoreBridge_nativeSendFile(JNIEnv* env, jobject thiz, jstring peer, jstring path) {
    const char* p = (*env)->GetStringUTFChars(env, peer, NULL);
    const char* f = (*env)->GetStringUTFChars(env, path, NULL);
    int rc = woo_send_file(p, f);
    (*env)->ReleaseStringUTFChars(env, peer, p);
    (*env)->ReleaseStringUTFChars(env, path, f);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_wiglywoo_CoreBridge_nativeSendFd(JNIEnv* env, jobject thiz, jstring peer, jint fd, jstring name, jlong size) {
    const char* p = (*env)->GetStringUTFChars(env, peer, NULL);
    const char* n = (*env)->GetStringUTFChars(env, name, NULL);
    int rc = woo_send_fd(p, (int)fd, n, (long long)size);
    (*env)->ReleaseStringUTFChars(env, peer, p);
    (*env)->ReleaseStringUTFChars(env, name, n);
    return rc;
}

JNIEXPORT void JNICALL
Java_com_wiglywoo_CoreBridge_nativeTrust(JNIEnv* env, jobject thiz, jstring fp, jint ok) {
    const char* f = (*env)->GetStringUTFChars(env, fp, NULL);
    woo_trust(f, ok);
    (*env)->ReleaseStringUTFChars(env, fp, f);
}

JNIEXPORT void JNICALL
Java_com_wiglywoo_CoreBridge_nativeCancel(JNIEnv* env, jobject thiz) {
    woo_cancel();
}
