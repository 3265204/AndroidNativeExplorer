LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := ane_pty
LOCAL_SRC_FILES := ane_pty.cpp
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
