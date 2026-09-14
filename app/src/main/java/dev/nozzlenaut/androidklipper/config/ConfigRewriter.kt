package dev.nozzlenaut.androidklipper.config

/** Rewrites only a temporary runtime copy of a Klipper config. */
object ConfigRewriter {
    data class McuRef(
        val section: String,
        val sourceSerial: String,
        val usbSerialHint: String?
    )

    private val sectionRegex = Regex("^\\s*\\[(mcu(?:\\s+[^]]+)?)\\]\\s*$", RegexOption.IGNORE_CASE)
    private val serialRegex = Regex("^(\\s*)serial\\s*:\\s*(\\S+)\\s*$", RegexOption.IGNORE_CASE)

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
        mapping: Map<String, String>
    ): String {
        val out = mutableListOf<String>()
        var section: String? = null

        text.lines().forEach { line ->
            val sec = sectionRegex.matchEntire(line)
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
            val serial = if (active != null) serialRegex.matchEntire(line) else null
            val target = active?.let { mapping[it] }
            if (serial != null && target != null) {
                out += "${serial.groupValues[1]}serial: $target"
            } else {
                out += line
            }
        }
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
