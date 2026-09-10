package dev.nozzlenaut.androidklipper

import android.content.Context

object HostStatusStore {
    private const val PREFS = "androidklipper_host"
    private const val KEY_REPORT = "last_report"

    fun save(context: Context, report: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REPORT, report)
            .apply()
    }

    fun load(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REPORT, null)
}
