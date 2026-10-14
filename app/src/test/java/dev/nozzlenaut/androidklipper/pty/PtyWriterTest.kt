package dev.nozzlenaut.androidklipper.pty

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PtyWriterTest {
    /** Fake clock + PTY: each empty write consumes the 100 ms native timeout. */
    private class Fake(private val script: List<Int>) {
        var now = 1_000L
        var calls = 0
        val seen = mutableListOf<Pair<Int, Int>>()
        fun chunk(offset: Int, remaining: Int): Int {
            seen += offset to remaining
            val step = if (calls < script.size) script[calls] else script.last()
            calls++
            val n = if (step > remaining) remaining else step
            if (n <= 0) now += 100
            return n
        }
    }

    @Test
    fun writesEverythingWhenPtyAcceptsIt() {
        val fake = Fake(listOf(Int.MAX_VALUE))
        val written = PtyWriter.writeAll(
            length = 4096, isRunning = { true }, nowMs = { fake.now }, writeChunk = fake::chunk
        )
        assertEquals(4096, written)
        assertEquals(1, fake.calls)
    }

    @Test
    fun partialWritesAdvanceTheOffset() {
        val fake = Fake(listOf(1000, 1000, 5000))
        val written = PtyWriter.writeAll(
            length = 4096, isRunning = { true }, nowMs = { fake.now }, writeChunk = fake::chunk
        )
        assertEquals(4096, written)
        assertEquals(listOf(0 to 4096, 1000 to 3096, 2000 to 2096), fake.seen)
    }

    @Test
    fun progressResetsTheStallTimer() {
        // Four empty writes (400 ms) then progress, repeated: never reaches 1 s.
        val fake = Fake(listOf(0, 0, 0, 0, 100, 0, 0, 0, 0, 100, 5000))
        val written = PtyWriter.writeAll(
            length = 1000, isRunning = { true }, nowMs = { fake.now },
            stallLimitMs = 1_000, writeChunk = fake::chunk
        )
        assertEquals(1000, written)
    }

    @Test
    fun stallBeyondTheLimitThrowsStallException() {
        val fake = Fake(listOf(500, 0))
        try {
            PtyWriter.writeAll(
                length = 2000, isRunning = { true }, nowMs = { fake.now },
                stallLimitMs = 1_000, writeChunk = fake::chunk
            )
            fail("expected StallException")
        } catch (e: PtyWriter.StallException) {
            assertTrue(e.message!!.contains("500/2000"))
        }
        // The stall clock starts when the first empty attempt returns, so 11
        // empty attempts (0, 100 ... 1000 ms) are needed to reach the limit.
        assertEquals(1 + 11, fake.calls)
    }

    @Test
    fun stallExceptionIsAnIoException() {
        val fake = Fake(listOf(0))
        try {
            PtyWriter.writeAll(
                length = 10, isRunning = { true }, nowMs = { fake.now },
                stallLimitMs = 200, writeChunk = fake::chunk
            )
            fail("expected exception")
        } catch (e: IOException) {
            assertTrue(e is PtyWriter.StallException)
        }
    }

    @Test
    fun hardErrorIsReportedImmediatelyAndIsNotAStall() {
        val fake = Fake(listOf(100, -1))
        try {
            PtyWriter.writeAll(
                length = 1000, isRunning = { true }, nowMs = { fake.now }, writeChunk = fake::chunk
            )
            fail("expected IOException")
        } catch (e: PtyWriter.StallException) {
            fail("hard error must not be classified as a stall")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("100/1000"))
        }
        assertEquals(2, fake.calls)
    }

    @Test
    fun doesNothingWhenAlreadyStopped() {
        val fake = Fake(listOf(10))
        val written = PtyWriter.writeAll(
            length = 100, isRunning = { false }, nowMs = { fake.now }, writeChunk = fake::chunk
        )
        assertEquals(0, written)
        assertEquals(0, fake.calls)
    }

    @Test
    fun shutdownMidWriteStopsQuietlyWithoutThrowing() {
        val fake = Fake(listOf(0))
        var running = true
        val written = PtyWriter.writeAll(
            length = 100,
            isRunning = { running },
            nowMs = { fake.now },
            onStall = { if (it >= 300) running = false },
            writeChunk = fake::chunk
        )
        assertEquals(0, written)
        assertFalse(running)
        assertTrue(fake.calls < 10)
    }

    @Test
    fun onStallReportsGrowingStallTime() {
        val fake = Fake(listOf(0, 0, 0, 100, 0, 5000))
        val seen = mutableListOf<Long>()
        PtyWriter.writeAll(
            length = 500, isRunning = { true }, nowMs = { fake.now },
            onStall = { seen += it }, writeChunk = fake::chunk
        )
        // first empty attempt starts the stall at 0 ms; the timer restarts after progress
        assertEquals(listOf(0L, 100L, 200L, 0L), seen)
    }

    @Test
    fun exactlyAtTheLimitCountsAsStalled() {
        val fake = Fake(listOf(0))
        try {
            PtyWriter.writeAll(
                length = 1, isRunning = { true }, nowMs = { fake.now },
                stallLimitMs = 300, writeChunk = fake::chunk
            )
            fail("expected StallException")
        } catch (e: PtyWriter.StallException) {
            assertEquals(4, fake.calls) // stalled 0,100,200,300 ms
        }
    }
}
