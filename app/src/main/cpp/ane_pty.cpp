#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <pty.h>
#include <signal.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

namespace {

struct PtyHandle {
    int master_fd;
    pid_t child_pid;
    std::mutex mutex;
    bool terminating = false;

    PtyHandle(int fd, pid_t pid) : master_fd(fd), child_pid(pid) {}
};

std::string to_string(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* utf = env->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) return {};
    std::string result(utf);
    env->ReleaseStringUTFChars(value, utf);
    return result;
}

std::vector<std::string> to_strings(JNIEnv* env, jobjectArray values) {
    std::vector<std::string> result;
    if (values == nullptr) return result;
    const jsize count = env->GetArrayLength(values);
    result.reserve(static_cast<size_t>(count));
    for (jsize index = 0; index < count; ++index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        result.push_back(to_string(env, value));
        env->DeleteLocalRef(value);
    }
    return result;
}

void throw_io(JNIEnv* env, const char* operation) {
    jclass type = env->FindClass("java/io/IOException");
    if (type == nullptr) return;
    std::string message(operation);
    message.append(": ").append(std::strerror(errno));
    env->ThrowNew(type, message.c_str());
}

PtyHandle* from_handle(jlong handle) {
    return reinterpret_cast<PtyHandle*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_ane_filemanager_pluginmanager_pty_NativePtyBridge_spawn(
        JNIEnv* env,
        jobject,
        jstring executable_value,
        jobjectArray argument_values,
        jstring working_directory_value,
        jobjectArray environment_key_values,
        jobjectArray environment_value_values,
        jint rows,
        jint columns) {
    const std::string executable = to_string(env, executable_value);
    const std::string working_directory = to_string(env, working_directory_value);
    const std::vector<std::string> arguments = to_strings(env, argument_values);
    const std::vector<std::string> environment_keys = to_strings(env, environment_key_values);
    const std::vector<std::string> environment_values = to_strings(env, environment_value_values);
    if (executable.empty() || environment_keys.size() != environment_values.size()) {
        errno = EINVAL;
        throw_io(env, "Invalid PTY request");
        return 0;
    }

    winsize window{};
    window.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    window.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);
    int master_fd = -1;
    const pid_t child = forkpty(&master_fd, nullptr, nullptr, &window);
    if (child < 0) {
        throw_io(env, "forkpty");
        return 0;
    }
    if (child == 0) {
        if (!working_directory.empty() && chdir(working_directory.c_str()) != 0) _exit(126);
        setenv("TERM", "xterm-256color", 0);
        setenv("COLORTERM", "truecolor", 0);
        setenv("ANE_PTY", "1", 1);
        for (size_t index = 0; index < environment_keys.size(); ++index) {
            setenv(environment_keys[index].c_str(), environment_values[index].c_str(), 1);
        }
        std::vector<char*> argv;
        argv.reserve(arguments.size() + 2);
        argv.push_back(const_cast<char*>(executable.c_str()));
        for (const std::string& argument : arguments) {
            argv.push_back(const_cast<char*>(argument.c_str()));
        }
        argv.push_back(nullptr);
        execv(executable.c_str(), argv.data());
        _exit(127);
    }

    fcntl(master_fd, F_SETFD, FD_CLOEXEC);
    auto* handle = new PtyHandle(master_fd, child);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ane_filemanager_pluginmanager_pty_NativePtyBridge_read(
        JNIEnv* env, jobject, jlong handle_value, jbyteArray buffer) {
    PtyHandle* handle = from_handle(handle_value);
    if (handle == nullptr || buffer == nullptr) return -1;
    const jsize capacity = env->GetArrayLength(buffer);
    jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
    if (bytes == nullptr) return -1;
    int master_fd;
    {
        std::lock_guard<std::mutex> lock(handle->mutex);
        master_fd = handle->master_fd;
    }
    if (master_fd < 0) {
        env->ReleaseByteArrayElements(buffer, bytes, JNI_ABORT);
        return -1;
    }
    ssize_t count;
    do {
        count = ::read(master_fd, bytes, static_cast<size_t>(capacity));
    } while (count < 0 && errno == EINTR);
    const int saved_errno = errno;
    env->ReleaseByteArrayElements(buffer, bytes, count > 0 ? 0 : JNI_ABORT);
    errno = saved_errno;
    if (count < 0 && errno == EIO) return 0;
    if (count < 0 && errno != EBADF) throw_io(env, "PTY read");
    return static_cast<jint>(count);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ane_filemanager_pluginmanager_pty_NativePtyBridge_write(
        JNIEnv* env, jobject, jlong handle_value, jbyteArray input) {
    PtyHandle* handle = from_handle(handle_value);
    if (handle == nullptr || input == nullptr) return -1;
    const jsize length = env->GetArrayLength(input);
    jbyte* bytes = env->GetByteArrayElements(input, nullptr);
    if (bytes == nullptr) return -1;
    size_t written = 0;
    {
        std::lock_guard<std::mutex> lock(handle->mutex);
        while (written < static_cast<size_t>(length) && handle->master_fd >= 0) {
            const ssize_t count = ::write(
                handle->master_fd,
                bytes + written,
                static_cast<size_t>(length) - written
            );
            if (count > 0) {
                written += static_cast<size_t>(count);
            } else if (count < 0 && errno == EINTR) {
                continue;
            } else {
                break;
            }
        }
    }
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ane_filemanager_pluginmanager_pty_NativePtyBridge_resize(
        JNIEnv*, jobject, jlong handle_value, jint rows, jint columns) {
    PtyHandle* handle = from_handle(handle_value);
    if (handle == nullptr) return JNI_FALSE;
    winsize window{};
    window.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 1);
    window.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 1);
    std::lock_guard<std::mutex> lock(handle->mutex);
    return handle->master_fd >= 0 && ioctl(handle->master_fd, TIOCSWINSZ, &window) == 0
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ane_filemanager_pluginmanager_pty_NativePtyBridge_signal(
        JNIEnv*, jobject, jlong handle_value, jint signal_value) {
    PtyHandle* handle = from_handle(handle_value);
    if (handle == nullptr || signal_value <= 0) return JNI_FALSE;
    if (kill(-handle->child_pid, signal_value) == 0) return JNI_TRUE;
    return errno == ESRCH && kill(handle->child_pid, signal_value) == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ane_filemanager_pluginmanager_pty_NativePtyBridge_terminate(
        JNIEnv*, jobject, jlong handle_value) {
    PtyHandle* handle = from_handle(handle_value);
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> lock(handle->mutex);
    if (handle->terminating) return;
    handle->terminating = true;
    if (kill(-handle->child_pid, SIGHUP) != 0 && errno == ESRCH) {
        kill(handle->child_pid, SIGHUP);
    }
    if (handle->master_fd >= 0) {
        close(handle->master_fd);
        handle->master_fd = -1;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ane_filemanager_pluginmanager_pty_NativePtyBridge_waitAndDestroy(
        JNIEnv*, jobject, jlong handle_value) {
    PtyHandle* handle = from_handle(handle_value);
    if (handle == nullptr) return -1;
    int status = 0;
    pid_t waited;
    do {
        waited = waitpid(handle->child_pid, &status, 0);
    } while (waited < 0 && errno == EINTR);
    {
        std::lock_guard<std::mutex> lock(handle->mutex);
        if (handle->master_fd >= 0) close(handle->master_fd);
        handle->master_fd = -1;
    }
    int exit_code = -1;
    int signal_value = -1;
    if (waited > 0) {
        if (WIFEXITED(status)) exit_code = WEXITSTATUS(status);
        if (WIFSIGNALED(status)) signal_value = WTERMSIG(status);
    }
    delete handle;
    const uint64_t encoded =
        (static_cast<uint64_t>(static_cast<uint32_t>(exit_code)) << 32U) |
        static_cast<uint32_t>(signal_value);
    return static_cast<jlong>(encoded);
}
