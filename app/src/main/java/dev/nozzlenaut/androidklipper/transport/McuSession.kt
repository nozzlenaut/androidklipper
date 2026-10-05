package dev.nozzlenaut.androidklipper.transport

import dev.nozzlenaut.androidklipper.pty.PtyBridge

/**
 * Common contract for a Klipper MCU transport.
 *
 * Klippy only sees the PTY. The implementation can be local Android USB or a
 * remote AndroidKlipper Bridge TCP stream.
 */
interface McuSession : AutoCloseable {
    val stableId: String
    val pty: PtyBridge

    fun start()

    fun statsSnapshot(): String
}
