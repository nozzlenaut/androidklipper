package dev.nozzlenaut.androidklipper.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigRewriterTest {
    @Test
    fun extractsAndRewritesMultipleMcuSections() {
        val source = """
            [mcu]
            serial: /dev/serial/by-id/usb-Klipper_stm32f446xx_3F001E001450535556323420-if00
            restart_method: command

            [mcu toolhead]
            serial: /dev/serial/by-id/usb-Klipper_rp2040_E6616407E30D6F2A-if00
        """.trimIndent()

        val refs = ConfigRewriter.extractUsbMcuRefs(source)
        assertEquals(2, refs.size)
        assertEquals("3F001E001450535556323420", refs[0].usbSerialHint)
        assertEquals("E6616407E30D6F2A", refs[1].usbSerialHint)

        val rewritten = ConfigRewriter.rewriteForRuntime(
            source,
            mapOf("mcu" to "/dev/pts/3", "mcu toolhead" to "/dev/pts/4")
        )
        assertTrue(rewritten.contains("serial: /dev/pts/3"))
        assertTrue(rewritten.contains("serial: /dev/pts/4"))
        assertEquals(2, Regex("baud: 115200").findAll(rewritten).count())
    }

    @Test
    fun doesNotTouchCanbusMcu() {
        val source = """
            [mcu toolhead]
            canbus_uuid: 97943c496528
        """.trimIndent()
        val rewritten = ConfigRewriter.rewriteForRuntime(source, mapOf("mcu toolhead" to "/dev/pts/4"))
        assertEquals(source, rewritten)
    }

    @Test
    fun preservesInlineCommentsAndExistingBaud() {
        val source = """
            [mcu] # main controller
            baud: 115200
            serial: /dev/serial/by-id/usb-Klipper_stm32f446xx_ABC123-if00  # keep this note

            [printer]
            kinematics: corexy
        """.trimIndent()

        val refs = ConfigRewriter.extractUsbMcuRefs(source)
        assertEquals(1, refs.size)
        assertEquals("ABC123", refs.single().usbSerialHint)

        val rewritten = ConfigRewriter.rewriteForRuntime(
            source,
            mapOf("mcu" to "/dev/pts/7")
        )
        assertTrue(rewritten.contains("serial: /dev/pts/7  # keep this note"))
        assertEquals(1, Regex("baud: 115200").findAll(rewritten).count())
        assertTrue(rewritten.contains("[mcu] # main controller"))
    }

    @Test
    fun leavesUnmappedUsbMcuUntouched() {
        val source = """
            [mcu]
            serial: /dev/serial/by-id/usb-Klipper_stm32f446xx_MAIN-if00

            [mcu toolhead]
            serial: /dev/serial/by-id/usb-Klipper_rp2040_TOOL-if00
        """.trimIndent()

        val rewritten = ConfigRewriter.rewriteForRuntime(
            source,
            mapOf("mcu" to "/dev/pts/8")
        )
        assertTrue(rewritten.contains("serial: /dev/pts/8"))
        assertTrue(rewritten.contains("usb-Klipper_rp2040_TOOL-if00"))
        assertEquals(1, Regex("baud: 115200").findAll(rewritten).count())
        assertFalse(rewritten.contains("serial: /dev/pts/9"))
    }
}
