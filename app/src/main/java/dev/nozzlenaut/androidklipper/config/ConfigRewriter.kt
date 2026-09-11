package dev.nozzlenaut.androidklipper.config

/** Rewrites only a temporary runtime copy of a Klipper config. */
object ConfigRewriter {
    data class McuRef(
        val section: String,
        val sourceSerial: String,
        val usbSerialHint: String?
    )

    private val sectionRegex = Regex(
        "^\\s*\\[(mcu(?:\\s+[^]]+)?)\\]\\s*(?:[#;].*)?$",
        RegexOption.IGNORE_CASE
    )
    private val serialRegex = Regex(
        "^(\\s*)serial\\s*:\\s*(\\S+)(\\s*(?:[#;].*)?)$",
        RegexOption.IGNORE_CASE
    )
    private val baudRegex = Regex("^\\s*baud\\s*:", RegexOption.IGNORE_CASE)

    fun extractUsbMcuRefs(text: String): List<McuRef> {
        val out = mutableListOf<McuRef>()
        var section: String? = null
        text.lineSequence().forEach { line ->
            val sec = sectionRegex.matchEntire(line)
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
        mapping: Map<String, String>,
        ptyBaud: Int = 115200
    ): String {
        val out = mutableListOf<String>()
        var section: String? = null
        var rewrittenCurrentSection = false
        var currentHasBaud = false

        fun flushBaudIfNeeded() {
            if (rewrittenCurrentSection && !currentHasBaud) out += "baud: $ptyBaud"
            rewrittenCurrentSection = false
            currentHasBaud = false
        }

        text.lines().forEach { line ->
            val sec = sectionRegex.matchEntire(line)
            if (sec != null) {
                flushBaudIfNeeded()
                section = sec.groupValues[1]
                out += line
                return@forEach
            }
            if (line.trimStart().startsWith("[")) {
                flushBaudIfNeeded()
                section = null
                out += line
                return@forEach
            }

            val active = section
            if (active != null && baudRegex.containsMatchIn(line)) currentHasBaud = true
            val serial = if (active != null) serialRegex.matchEntire(line) else null
            val target = active?.let { mapping[it] }
            if (serial != null && target != null) {
                val indent = serial.groupValues[1]
                val suffix = serial.groupValues[3]
                out += "$indent" + "serial: $target" + suffix
                rewrittenCurrentSection = true
            } else {
                out += line
            }
        }
        flushBaudIfNeeded()
        return out.joinToString("\n")
    }

    internal fun extractUsbSerialHint(path: String): String? {
        if (!path.contains("/dev/serial/by-id/")) return null
        val base = path.substringAfterLast('/')
        val withoutIf = base.replace(Regex("-if\\d+$"), "")
        val candidate = withoutIf.substringAfterLast('_', missingDelimiterValue = "")
        return candidate.takeIf { it.isNotBlank() }
    }
}
