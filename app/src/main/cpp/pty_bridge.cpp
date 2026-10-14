#include <jni.h>
#include <fcntl.h>
#include <errno.h>
#include <poll.h>
#include <stdlib.h>
#include <unistd.h>
#include <termios.h>
#include <string>
#include <vector>
#include <time.h>

static long long monotonic_ms() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000LL + ts.tv_nsec / 1000000LL;
}

static std::string make_result(int fd, const char* path) {
    return std::to_string(fd) + "\n" + std::string(path);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_nozzlenaut_androidklipper_pty_PtyBridge_nativeCreate(JNIEnv* env, jclass) {
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0) return env->NewStringUTF("-1\n");
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master);
        return env->NewStringUTF("-1\n");
    }
    char path[256] = {0};
    if (ptsname_r(master, path, sizeof(path)) != 0) {
        close(master);
        return env->NewStringUTF("-1\n");
    }
    // Klipper exchanges binary protocol bytes. PTYs default to terminal
    // processing (canonical mode, echo, CR/LF transforms), so make the slave
    // byte-transparent before Python ever opens it.
    int slave = open(path, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) {
        close(master);
        return env->NewStringUTF("-1\n");
    }
    struct termios tio{};
    if (tcgetattr(slave, &tio) != 0) {
        close(slave);
        close(master);
        return env->NewStringUTF("-1\n");
    }
    cfmakeraw(&tio);
    tio.c_cflag |= CLOCAL | CREAD;
    if (tcsetattr(slave, TCSANOW, &tio) != 0) {
        close(slave);
        close(master);
        return env->NewStringUTF("-1\n");
    }
    close(slave);

    // The master side must never block a bridge thread inside JNI. A blocked
    // write() cannot be woken by Thread.interrupt() or by closing the USB
    // connection, so a stalled Klippy would freeze the USB reader forever.
    // Reads are already poll()-guarded and writes poll(POLLOUT) with a timeout.
    const int flags = fcntl(master, F_GETFL, 0);
    if (flags < 0 || fcntl(master, F_SETFL, flags | O_NONBLOCK) != 0) {
        close(master);
        return env->NewStringUTF("-1\n");
    }

    const std::string result = make_result(master, path);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nozzlenaut_androidklipper_pty_PtyBridge_nativeRead(
        JNIEnv* env, jclass, jint fd, jbyteArray buffer, jint timeoutMs) {
    if (fd < 0) return -1;
    struct pollfd pfd{};
    pfd.fd = fd;
    pfd.events = POLLIN;
    const int pr = poll(&pfd, 1, timeoutMs);
    if (pr <= 0) return pr;

    // devpts reports HUP while no process has the slave side open. Without
    // handling that explicitly, poll() returns immediately and the Java reader
    // can hot-loop until Klippy attaches.
    if ((pfd.revents & (POLLHUP | POLLERR | POLLNVAL))
            && !(pfd.revents & POLLIN)) {
        usleep(10 * 1000);
        return 0;
    }

    const jsize cap = env->GetArrayLength(buffer);
    jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
    const ssize_t n = read(fd, bytes, static_cast<size_t>(cap));
    env->ReleaseByteArrayElements(buffer, bytes, n > 0 ? 0 : JNI_ABORT);
    if (n < 0 && errno == EIO) {
        usleep(10 * 1000);
        return 0;
    }
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) {
        return 0;
    }
    return static_cast<jint>(n);
}

// Writes data[offset, offset+length) to the PTY master, waiting at most
// timeout_ms in total for the slave side to drain.
//
// Returns the number of bytes written. That can be less than length when the
// timeout expires (the caller decides whether to retry or give up), and 0 when
// nothing could be written in time. Returns -1 for bad arguments or a hard
// error with nothing written.
extern "C" JNIEXPORT jint JNICALL
Java_dev_nozzlenaut_androidklipper_pty_PtyBridge_nativeWrite(
        JNIEnv* env, jclass, jint fd, jbyteArray data, jint offset, jint length,
        jint timeout_ms) {
    if (fd < 0 || data == nullptr) return -1;
    const jsize array_len = env->GetArrayLength(data);
    if (offset < 0 || length < 0 || length > array_len - offset) return -1;
    if (length == 0) return 0;

    // Copy out instead of pinning: this call can wait for a while and must not
    // hold the Java array (or the GC) hostage while it does.
    std::vector<jbyte> local(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, offset, length, local.data());
    if (env->ExceptionCheck()) return -1;

    const long long deadline = monotonic_ms() + (timeout_ms > 0 ? timeout_ms : 0);
    ssize_t total = 0;
    bool hard_error = false;
    while (total < length) {
        const ssize_t n = write(fd, local.data() + total, static_cast<size_t>(length - total));
        if (n > 0) {
            total += n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            const long long remaining = deadline - monotonic_ms();
            if (remaining <= 0) break;
            struct pollfd pfd{};
            pfd.fd = fd;
            pfd.events = POLLOUT;
            const int pr = poll(&pfd, 1, static_cast<int>(remaining));
            if (pr < 0 && errno == EINTR) continue;
            if (pr <= 0) break;  // timed out (or poll failed): report what we wrote
            if (pfd.revents & (POLLERR | POLLNVAL)) {
                hard_error = true;
                break;
            }
            continue;
        }
        hard_error = true;  // anything other than EINTR/EAGAIN
        break;
    }
    // 0 means "slave is slow, retry"; -1 means the fd is unusable.
    if (hard_error && total == 0) return -1;
    return static_cast<jint>(total);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nozzlenaut_androidklipper_pty_PtyBridge_nativeClose(JNIEnv*, jclass, jint fd) {
    if (fd >= 0) close(fd);
}
