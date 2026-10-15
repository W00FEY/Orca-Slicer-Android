#pragma once

#include <jni.h>
#include <string>

namespace orca_android {

std::string to_std_string(JNIEnv *env, jstring str);
jstring     to_jstring(JNIEnv *env, const std::string &str);

} // namespace orca_android
