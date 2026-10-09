package dev.nozzlenaut.androidklipper

internal enum class HostReportSource { LIVE, SAVED }

/** Saved reports describe a previous moment, not the outcome of a new USB startup. */
internal object HostReportPolicy {
    fun canCancelAutoStart(report: String, source: HostReportSource): Boolean =
        source == HostReportSource.LIVE &&
            (report.contains("Host startup ERROR") ||
                report.contains("Moonraker ERROR") ||
                report.startsWith("USB bridge error"))
}
