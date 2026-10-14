import dev.nozzlenaut.androidklipper.pty.PtyBridge;

import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Native PTY bridge tests. Run via scripts/native-tests/run.sh (Linux, JDK with jni.h). */
public class PtyNativeTest {
    static int failures = 0;
    static int passed = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else    { failures++; System.out.println("FAIL  " + name + "  -- " + detail); }
    }

    static class Pty {
        final int fd; final String path; RandomAccessFile slave;
        Pty() throws Exception {
            String r = PtyBridge.nativeCreate();
            fd = Integer.parseInt(r.substring(0, r.indexOf('\n')));
            path = r.substring(r.indexOf('\n') + 1);
            slave = new RandomAccessFile(path, "rw");
        }
    }

    static byte[] pattern(int n, int seed) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ((i * 31 + seed) & 0xff);
        return b;
    }

    public static void main(String[] args) throws Exception {
        roundTrip();
        nonReadingSlaveDoesNotBlock();
        partialWritesAreAccountedExactly();
        streamIntegrityUnderBackpressure();
        writeAfterSlaveClosed();
        readTimeoutAndData();
        argumentValidation();
        closedFdIsHardError();
        rawModeIsByteTransparent();
        System.out.printf("%n%d passed, %d failed%n", passed, failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static void roundTrip() throws Exception {
        Pty p = new Pty();
        byte[] msg = pattern(300, 7);
        int w = PtyBridge.nativeWrite(p.fd, msg, 0, msg.length, 500);
        byte[] got = new byte[300];
        p.slave.readFully(got);
        check("master->slave round trip", w == 300 && Arrays.equals(msg, got), "w=" + w);
        byte[] back = pattern(64, 99);
        p.slave.write(back);
        byte[] buf = new byte[256];
        int n = PtyBridge.nativeRead(p.fd, buf, 500);
        check("slave->master round trip", n == 64 && Arrays.equals(back, Arrays.copyOf(buf, 64)), "n=" + n);
        p.slave.close(); PtyBridge.nativeClose(p.fd);
    }

    static void nonReadingSlaveDoesNotBlock() throws Exception {
        Pty p = new Pty();
        byte[] big = new byte[1 << 20];
        long t0 = System.nanoTime();
        int w = PtyBridge.nativeWrite(p.fd, big, 0, big.length, 200);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("non-reading slave: returns after the timeout instead of hanging",
              ms >= 150 && ms < 1500, "elapsed=" + ms + "ms");
        check("non-reading slave: reports a partial write",
              w >= 0 && w < big.length, "w=" + w);
        // The baseline implementation blocked here forever; the writer thread
        // could not even be interrupted. Prove a thread in this call is reclaimable.
        AtomicLong result = new AtomicLong(-2);
        Thread t = new Thread(() -> result.set(PtyBridge.nativeWrite(p.fd, big, 0, big.length, 300)));
        t.start(); t.join(3000);
        check("non-reading slave: bridge thread finishes on its own", !t.isAlive(), "thread still alive");
        p.slave.close(); PtyBridge.nativeClose(p.fd);
    }

    static void partialWritesAreAccountedExactly() throws Exception {
        Pty p = new Pty();
        byte[] data = pattern(200_000, 3);
        int off = 0; int stalls = 0;
        while (off < data.length && stalls < 3) {
            int w = PtyBridge.nativeWrite(p.fd, data, off, data.length - off, 50);
            if (w < 0) break;
            if (w == 0) stalls++;
            off += w;
            if (off < data.length && w == 0) break; // slave is full and nobody is reading
        }
        // Drain what the PTY actually accepted and compare to the accounted prefix.
        byte[] drained = new byte[off];
        int got = 0;
        p.slave.getChannel(); // keep RandomAccessFile open
        while (got < off) {
            int n = p.slave.read(drained, got, off - got);
            if (n <= 0) break;
            got += n;
        }
        check("partial writes: bytes accepted == bytes reported",
              got == off && Arrays.equals(drained, Arrays.copyOf(data, off)),
              "reported=" + off + " drained=" + got);
        p.slave.close(); PtyBridge.nativeClose(p.fd);
    }

    static void streamIntegrityUnderBackpressure() throws Exception {
        Pty p = new Pty();
        final int total = 2 << 20;
        byte[] data = pattern(total, 11);
        AtomicLong sum = new AtomicLong();
        AtomicBoolean done = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try {
                byte[] buf = new byte[3000];
                long read = 0; long s = 0; int slowdown = 0;
                while (read < total) {
                    int n = p.slave.read(buf, 0, buf.length);
                    if (n <= 0) break;
                    for (int i = 0; i < n; i++) s += (buf[i] & 0xff) * (long) (((read + i) % 251) + 1);
                    read += n;
                    if ((++slowdown % 4) == 0) Thread.sleep(3); // slow, bursty consumer so the PTY fills up
                }
                sum.set(s);
            } catch (Exception e) { sum.set(-1); }
            done.set(true);
        });
        reader.start();
        int off = 0; long retries = 0;
        long t0 = System.nanoTime();
        while (off < total && (System.nanoTime() - t0) < 60_000_000_000L) {
            int len = Math.min(4096, total - off);
            long c0 = System.nanoTime();
            int w = PtyBridge.nativeWrite(p.fd, data, off, len, 100);
            long callMs = (System.nanoTime() - c0) / 1_000_000;
            if (w < 0) break;
            // Back-pressure shows up as a call that had to wait for the reader
            // (POLLOUT) or that returned short / empty.
            if (w < len || callMs >= 2) retries++;
            off += w;
        }
        reader.join(10_000);
        long expect = 0;
        for (int i = 0; i < total; i++) expect += (data[i] & 0xff) * (long) ((i % 251) + 1);
        check("2 MiB stream through a slow bursty reader arrives intact",
              off == total && sum.get() == expect, "off=" + off + " sum=" + sum.get() + " expect=" + expect);
        check("stream test really exercised back-pressure (waited or short writes=" + retries + ")",
              retries > 0, "retries=" + retries);
        p.slave.close(); PtyBridge.nativeClose(p.fd);
    }

    static void writeAfterSlaveClosed() throws Exception {
        Pty p = new Pty();
        p.slave.close(); // Klippy closed the port (e.g. during a RESTART)
        byte[] small = pattern(512, 1);
        long t0 = System.nanoTime();
        int w = PtyBridge.nativeWrite(p.fd, small, 0, small.length, 200);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("slave closed: small write returns promptly without crashing", ms < 1000, "elapsed=" + ms + "ms w=" + w);
        byte[] big = new byte[1 << 20];
        t0 = System.nanoTime();
        int w2 = PtyBridge.nativeWrite(p.fd, big, 0, big.length, 200);
        ms = (System.nanoTime() - t0) / 1_000_000;
        check("slave closed: large write is still time-bounded", ms < 1500, "elapsed=" + ms + "ms w=" + w2);
        PtyBridge.nativeClose(p.fd);
    }

    static void readTimeoutAndData() throws Exception {
        Pty p = new Pty();
        byte[] buf = new byte[128];
        long t0 = System.nanoTime();
        int n = PtyBridge.nativeRead(p.fd, buf, 120);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("read with no data returns 0 after ~timeout", n == 0 && ms >= 90 && ms < 600, "n=" + n + " ms=" + ms);
        p.slave.close();
        t0 = System.nanoTime();
        n = PtyBridge.nativeRead(p.fd, buf, 100);
        ms = (System.nanoTime() - t0) / 1_000_000;
        check("read after slave closed does not hot-loop or error", n == 0 && ms < 600, "n=" + n + " ms=" + ms);
        PtyBridge.nativeClose(p.fd);
    }

    static void argumentValidation() throws Exception {
        Pty p = new Pty();
        byte[] b = new byte[16];
        check("write: offset beyond array is rejected", PtyBridge.nativeWrite(p.fd, b, 20, 1, 10) == -1, "");
        check("write: length beyond array is rejected", PtyBridge.nativeWrite(p.fd, b, 8, 9, 10) == -1, "");
        check("write: negative offset is rejected", PtyBridge.nativeWrite(p.fd, b, -1, 1, 10) == -1, "");
        check("write: zero length is a no-op", PtyBridge.nativeWrite(p.fd, b, 16, 0, 10) == 0, "");
        check("write: invalid fd is rejected", PtyBridge.nativeWrite(-1, b, 0, 4, 10) == -1, "");
        p.slave.close(); PtyBridge.nativeClose(p.fd);
    }

    static void closedFdIsHardError() throws Exception {
        Pty p = new Pty();
        p.slave.close();
        PtyBridge.nativeClose(p.fd);
        int w = PtyBridge.nativeWrite(p.fd, new byte[8], 0, 8, 50);
        check("closed fd reports -1 (so the caller can tell it from a slow slave)", w == -1, "w=" + w);
    }

    static void rawModeIsByteTransparent() throws Exception {
        Pty p = new Pty();
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i; // includes \r \n ^C ^D ^Z etc.
        PtyBridge.nativeWrite(p.fd, all, 0, all.length, 500);
        byte[] got = new byte[256];
        p.slave.readFully(got);
        check("all 256 byte values pass master->slave untouched", Arrays.equals(all, got), "");
        p.slave.write(all);
        byte[] buf = new byte[512]; int n = 0;
        while (n < 256) { int r = PtyBridge.nativeRead(p.fd, buf, 300); if (r <= 0) break; n += r; }
        check("all 256 byte values pass slave->master untouched",
              n == 256 && Arrays.equals(all, Arrays.copyOf(buf, 256)), "n=" + n);
        p.slave.close(); PtyBridge.nativeClose(p.fd);
    }
}
