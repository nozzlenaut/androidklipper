package dev.nozzlenaut.androidklipper.config

/** Rewrites only a temporary runtime copy of a Klipper config. */
object ConfigRewriter {
    data class McuRef(
        val section: String,
        val sourceSerial: String,
        val usbSerialHint: String?
    )

    private val mcuSectionRegex =
        Regex("^\\s*\\[(mcu(?:\\s+[^]]+)?)\\]\\s*$", RegexOption.IGNORE_CASE)
    private val anySectionRegex =
        Regex("^\\s*\\[([^]]+)]\\s*$", RegexOption.IGNORE_CASE)
    private val serialRegex =
        Regex("^(\\s*)serial\\s*:\\s*(\\S+)(.*)$", RegexOption.IGNORE_CASE)
    private val baudRegex =
        Regex("^\\s*baud\\s*:", RegexOption.IGNORE_CASE)
    private val restartRegex =
        Regex("^\\s*restart_method\\s*:", RegexOption.IGNORE_CASE)

    fun extractUsbMcuRefs(text: String): List<McuRef> {
        val out = mutableListOf<McuRef>()
        var section: String? = null
        text.lineSequence().forEach { line ->
            val sec = mcuSectionRegex.matchEntire(line)
            if (sec != null) {
                section = sec.groupValues[1]
                return@forEach
            }
            if (line.trimStart().startsWith("[")) {
                section = null
                return@forEach
            }
            val active = section ?: return@forEach
            val match = serialRegex.matchEntire(line) ?: return@forEach
            val value = match.groupValues[2]
            out += McuRef(active, value, extractUsbSerialHint(value))
        }
        return out
    }

    fun rewriteForRuntime(
        text: String,
        mapping: Map<String, String>
    ): String {
        val out = mutableListOf<String>()
        var section: String? = null

        text.lines().forEach { line ->
            val sec = mcuSectionRegex.matchEntire(line)
            if (sec != null) {
                section = sec.groupValues[1]
                out += line
                return@forEach
            }
            if (line.trimStart().startsWith("[")) {
                section = null
                out += line
                return@forEach
            }

            val active = section
            val target = active?.let { mapping[it] }
            val serial = if (target != null) serialRegex.matchEntire(line) else null
            when {
                serial != null -> {
                    val indent = serial.groupValues[1]
                    val old = serial.groupValues[2]
                    out += "${indent}serial: $target  # OLD: $old"
                }
                target != null && baudRegex.containsMatchIn(line) -> {
                    out += "# ANDROID_DISABLED baud (PTY pipe transport) | OLD: $line"
                }
                target != null && restartRegex.containsMatchIn(line) -> {
                    out += "# ANDROID_DISABLED restart_method (PTY pipe transport) | OLD: $line"
                }
                else -> out += line
            }
        }
        return out.joinToString("\n")
    }

    fun rewriteAndroidCompatibility(
        text: String,
        runtimeConfigDir: String,
        gcodeDir: String
    ): String {
        var source = text
            .replace("~/printer_data/config", runtimeConfigDir)
            .replace("~/printer_data/gcodes", gcodeDir)
            .replace(
                Regex("/home/[^/]+/printer_data/config"),
                runtimeConfigDir
            )
            .replace(
                Regex("/home/[^/]+/printer_data/gcodes"),
                gcodeDir
            )

        val lines = source.lines()
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val header = anySectionRegex.matchEntire(lines[i])
            if (header == null) {
                out += lines[i]
                i++
                continue
            }

            var end = i + 1
            while (end < lines.size && anySectionRegex.matchEntire(lines[end]) == null) end++
            val block = lines.subList(i, end)
            val sectionName = header.groupValues[1].trim()
            val disable =
                sectionName.equals("shaketune", ignoreCase = true) ||
                sectionName.startsWith("include K-ShakeTune/", ignoreCase = true) ||
                block.any {
                    it.trim().equals("sensor_type: temperature_host", ignoreCase = true)
                }

            if (disable) {
                block.forEach { line ->
                    out += if (line.isBlank()) line else "# ANDROID_DISABLED: $line"
                }
            } else {
                out += block
            }
            i = end
        }
        source = out.joinToString("\n")
        return source
    }

    internal fun extractUsbSerialHint(path: String): String? {
        if (!path.contains("/dev/serial/by-id/")) return null
        val base = path.substringAfterLast('/')
        val withoutIf = base.replace(Regex("-if\\d+$"), "")
        val candidate = withoutIf.substringAfterLast('_', missingDelimiterValue = "")
        return candidate.takeIf { it.isNotBlank() }
    }
}
