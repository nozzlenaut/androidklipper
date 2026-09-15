package dev.nozzlenaut.androidklipper.config

import org.junit.Assert.assertEquals
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
        assertEquals(0, Regex("baud: 115200").findAll(rewritten).count())
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
}
