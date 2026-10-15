#include "JniUtils.hpp"

#include <vector>

namespace orca_android {

std::string to_std_string(JNIEnv *env, jstring str)
{
    if (str == nullptr)
        return {};
    const char *chars = env->GetStringUTFChars(str, nullptr);
    std::string out(chars ? chars : "");
    env->ReleaseStringUTFChars(str, chars);
    return out;
}

jstring to_jstring(JNIEnv *env, const std::string &str)
{
    // NewStringUTF expects "modified UTF-8", which rejects 4-byte sequences
    // (emoji etc.). Go through a Java byte[] -> String(UTF-8) instead.
    jbyteArray bytes = env->NewByteArray(jsize(str.size()));
    env->SetByteArrayRegion(bytes, 0, jsize(str.size()), reinterpret_cast<const jbyte *>(str.data()));
    jclass    string_cls = env->FindClass("java/lang/String");
    jmethodID ctor       = env->GetMethodID(string_cls, "<init>", "([BLjava/lang/String;)V");
    jstring   charset    = env->NewStringUTF("UTF-8");
    auto      result     = static_cast<jstring>(env->NewObject(string_cls, ctor, bytes, charset));
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(string_cls);
    return result;
}

} // namespace orca_android
