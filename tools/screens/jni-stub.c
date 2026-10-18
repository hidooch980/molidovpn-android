/* JVM-only stand-in for libaether/libaether_jni, used ONLY by tools/render-screens.sh so the real
 * MainActivity can be rendered under Robolectric. Every call reports "not running / failed". */
#include <jni.h>
#define S(n) JNIEXPORT jstring JNICALL Java_com_molido_vpn_NativeCore_##n(JNIEnv *e, jclass c, ...) { return (*e)->NewStringUTF(e, ""); }
#define I(n) JNIEXPORT jint JNICALL Java_com_molido_vpn_NativeCore_##n(JNIEnv *e, jclass c, ...) { return -1; }
#define B(n) JNIEXPORT jboolean JNICALL Java_com_molido_vpn_NativeCore_##n(JNIEnv *e, jclass c, ...) { return JNI_FALSE; }
#define V(n) JNIEXPORT void JNICALL Java_com_molido_vpn_NativeCore_##n(JNIEnv *e, jclass c, ...) { }
I(nativePrepare) S(nativeLastResult) I(nativeRequestEmailCode) I(nativeConfirmEmailCode)
I(nativeStart) I(nativeStartProxy) I(nativeStop) B(nativeIsRunning) B(nativeIsReady)
S(nativeLastError) S(nativeLastLog) V(nativeAttach) V(nativeDetach)
