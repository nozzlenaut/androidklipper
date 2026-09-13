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
            baud: 250000
        """.trimIndent()

        val refs = ConfigRewriter.extractUsbMcuRefs(source)
        assertEquals(2, refs.size)
        assertEquals("3F001E001450535556323420", refs[0].usbSerialHint)
        assertEquals("E6616407E30D6F2A", refs[1].usbSerialHint)

        val rewritten = ConfigRewriter.rewriteForRuntime(
            source,
            mapOf("mcu" to "/dev/pts/3", "mcu toolhead" to "/dev/pts/4")
        )
        assertTrue(rewritten.contains(
            "serial: /dev/pts/3  # OLD: /dev/serial/by-id/usb-Klipper_stm32f446xx_3F001E001450535556323420-if00"
        ))
        assertTrue(rewritten.contains(
            "serial: /dev/pts/4  # OLD: /dev/serial/by-id/usb-Klipper_rp2040_E6616407E30D6F2A-if00"
        ))
        assertFalse(rewritten.lineSequence().any { it.trimStart().startsWith("baud:") })
        assertFalse(rewritten.lineSequence().any { it.trimStart().startsWith("restart_method:") })
        assertTrue(rewritten.contains("# ANDROID_DISABLED restart_method"))
    }

    @Test
    fun doesNotTouchCanbusMcu() {
        val source = """
            [mcu toolhead]
            canbus_uuid: 97943c496528
        """.trimIndent()
        val rewritten = ConfigRewriter.rewriteForRuntime(
            source,
            mapOf("mcu toolhead" to "/dev/pts/4")
        )
        assertEquals(source, rewritten)
    }

    @Test
    fun adaptsHostPathsAndDisablesHostOnlySections() {
        val source = """
            [include K-ShakeTune/*.cfg]
            [shaketune]

            [temperature_sensor NUC]
            sensor_type: temperature_host
            min_temp: -10
            max_temp: 100

            [virtual_sdcard]
            path: ~/printer_data/gcodes

            [save_variables]
            filename: ~/printer_data/config/variables.cfg
        """.trimIndent()

        val rewritten = ConfigRewriter.rewriteAndroidCompatibility(
            source,
            "/data/config",
            "/data/gcodes"
        )

        assertTrue(rewritten.contains("# ANDROID_DISABLED: [include K-ShakeTune/*.cfg]"))
        assertTrue(rewritten.contains("# ANDROID_DISABLED: [shaketune]"))
        assertTrue(rewritten.contains("# ANDROID_DISABLED: [temperature_sensor NUC]"))
        assertTrue(rewritten.contains("path: /data/gcodes"))
        assertTrue(rewritten.contains("filename: /data/config/variables.cfg"))
    }
}
