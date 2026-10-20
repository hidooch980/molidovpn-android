#include <jni.h>
#include <client/ovpncli.hpp>
#include <mutex>
#include <string>

namespace {
std::mutex parser_mutex;
constexpr int invalid = 1;
constexpr int auth_required = 2;
constexpr int external_pki = 4;
constexpr int key_password_required = 8;
constexpr int internal_error = 16;
constexpr jsize max_profile = 512 * 1024;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_molido_vpn_OpenVpnCore_nativeRevision(JNIEnv *env, jclass) {
    return env->NewStringUTF(MOLIDO_OPENVPN_COMMIT);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_molido_vpn_OpenVpnCore_nativeEvaluate(JNIEnv *env, jclass, jbyteArray input) {
    if (!input) return invalid;
    const jsize length = env->GetArrayLength(input);
    if (length <= 0 || length > max_profile) return invalid;
    try {
        std::string content(static_cast<size_t>(length), '\0');
        env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(content.data()));
        if (env->ExceptionCheck()) return internal_error;
        if (content.find('\0') != std::string::npos) return invalid;
        std::lock_guard<std::mutex> guard(parser_mutex);
        static openvpn::ClientAPI::OpenVPNClientHelper helper;
        openvpn::ClientAPI::Config config;
        config.content = std::move(content);
        const auto result = helper.eval_config(config);
        if (result.error) return invalid;
        return (result.autologin ? 0 : auth_required)
            | (result.externalPki ? external_pki : 0)
            | (result.privateKeyPasswordRequired ? key_password_required : 0);
    } catch (...) {
        // Parser messages may contain secrets: never expose or log them.
        return internal_error;
    }
}
