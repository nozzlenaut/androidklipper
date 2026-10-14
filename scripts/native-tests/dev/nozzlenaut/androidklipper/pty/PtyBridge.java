package dev.nozzlenaut.androidklipper.pty;

/** Test-only JNI shim: same class/method names as the Kotlin PtyBridge companion externals. */
public class PtyBridge {
    static { System.loadLibrary("androidklipper_pty"); }
    public static native String nativeCreate();
    public static native int nativeRead(int fd, byte[] buffer, int timeoutMs);
    public static native int nativeWrite(int fd, byte[] data, int offset, int length, int timeoutMs);
    public static native void nativeClose(int fd);
}
