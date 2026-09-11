#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdlib.h>
#include <unistd.h>
#include <string>

static std::string make_result(int master_fd, int slave_fd, const char* path) {
    return std::to_string(master_fd) + "\n"
        + std::to_string(slave_fd) + "\n"
        + std::string(path);
}

static int wait_fd(int fd, short events, int timeout_ms) {
    struct pollfd pfd{};
    pfd.fd = fd;
    pfd.events = events;
    while (true) {
        const int result = poll(&pfd, 1, timeout_ms);
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0) return result;
        if (pfd.revents & (POLLERR | POLLNVAL)) return -1;
        if (pfd.revents & events) return 1;
        // With the slave anchor held open we should not normally see HUP.
        // Treat it as no data instead of attempting a read which would return EIO.
        if (pfd.revents & POLLHUP) return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_nozzlenaut_androidklipper_pty_PtyBridge_nativeCreate(JNIEnv* env, jclass) {
    // Non-blocking mode prevents a stalled Python consumer from wedging the
    // Android USB reader thread indefinitely.
    int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK);
    if (master < 0) return env->NewStringUTF("-1\n-1\n");
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master);
        return env->NewStringUTF("-1\n-1\n");
    }

    char path[256] = {0};
    if (ptsname_r(master, path, sizeof(path)) != 0) {
        close(master);
        return env->NewStringUTF("-1\n-1\n");
    }

    // A PTY master reports POLLHUP/EIO while no slave descriptor is open.
    // Keep one private slave descriptor open for the bridge lifetime so the
    // USB reader can start before pySerial opens the public slave path.
    int slave_anchor = open(path, O_RDWR | O_NOCTTY | O_CLOEXEC | O_NONBLOCK);
    if (slave_anchor < 0) {
        close(master);
        return env->NewStringUTF("-1\n-1\n");
    }

    const std::string result = make_result(master, slave_anchor, path);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nozzlenaut_androidklipper_pty_PtyBridge_nativeRead(
        JNIEnv* env, jclass, jint fd, jbyteArray buffer, jint timeoutMs) {
    if (fd < 0) return -1;
    const int ready = wait_fd(fd, POLLIN, timeoutMs);
    if (ready <= 0) return ready;

    const jsize cap = env->GetArrayLength(buffer);
    jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
    ssize_t n;
    do {
        n = read(fd, bytes, static_cast<size_t>(cap));
    } while (n < 0 && errno == EINTR);
    if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EIO)) n = 0;
    env->ReleaseByteArrayElements(buffer, bytes, n > 0 ? 0 : JNI_ABORT);
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nozzlenaut_androidklipper_pty_PtyBridge_nativeWrite(
        JNIEnv* env, jclass, jint fd, jbyteArray data, jint timeoutMs) {
    if (fd < 0) return -1;
    const jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    ssize_t total = 0;

    while (total < len) {
        const ssize_t n = write(fd, bytes + total, static_cast<size_t>(len - total));
        if (n > 0) {
            total += n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            const int ready = wait_fd(fd, POLLOUT, timeoutMs);
            if (ready > 0) continue;
            if (total == 0) total = ready;
            break;
        }
        if (total == 0) total = -1;
        break;
    }

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return static_cast<jint>(total);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nozzlenaut_androidklipper_pty_PtyBridge_nativeClose(
        JNIEnv*, jclass, jint masterFd, jint slaveAnchorFd) {
    if (slaveAnchorFd >= 0) close(slaveAnchorFd);
    if (masterFd >= 0) close(masterFd);
}
