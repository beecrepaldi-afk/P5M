// SPDX-License-Identifier: AGPL-3.0-only
#pragma once
#include <android/log.h>

#define P5M_TAG "P5MVR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  P5M_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  P5M_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, P5M_TAG, __VA_ARGS__)
