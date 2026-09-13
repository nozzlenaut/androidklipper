#include <jni.h>
#include <fcntl.h>
#include <errno.h>
#include <poll.h>
#include <stdlib.h>
#include <unistd.h>
#include <string>
#include <termios.h>

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
    // Klipper's pipe transport expects an 8-bit-clean byte stream. PTYs
    // default to canonical terminal processing (echo, CR/LF translation,
    // signal characters), so force the slave side into raw mode once.
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

    // When no process currently has the PTY slave open, Linux/Android devpts
    // can report POLLHUP and read() can return EIO immediately. Treat that as
    // "no slave attached yet", not as readable data. Without this guard the
    // Android bridge can hot-loop at 100% CPU until Klippy opens the slave.
    if ((pfd.revents & POLLHUP) && !(pfd.revents & POLLIN)) {
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
    return static_cast<jint>(n);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nozzlenaut_androidklipper_pty_PtyBridge_nativeWrite(
        JNIEnv* env, jclass, jint fd, jbyteArray data) {
    if (fd < 0) return -1;
    const jsize len = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    ssize_t total = 0;
    while (total < len) {
        const ssize_t n = write(fd, bytes + total, static_cast<size_t>(len - total));
        if (n <= 0) break;
        total += n;
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return static_cast<jint>(total);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nozzlenaut_androidklipper_pty_PtyBridge_nativeClose(JNIEnv*, jclass, jint fd) {
    if (fd >= 0) close(fd);
}
