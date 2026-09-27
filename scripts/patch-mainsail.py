#!/usr/bin/env python3
from pathlib import Path
import sys

chart_path = Path(sys.argv[1])
actions_path = Path(sys.argv[2])
getters_path = Path(sys.argv[3])


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    if old not in text:
        raise SystemExit(f"Mainsail {label} patch point changed; inspect upstream before updating the pin")
    path.write_text(text.replace(old, new, 1), newline="\n")


replace_once(
    chart_path,
    "                name: 'PWM [%]',\n",
    "                name: 'Percent [%]',\n",
    "percent-axis label",
)

chart_tooltip_old = """        if (!outputRows) return ''

        const theme = this.$vuetify.theme.dark ? 'theme-dark' : ''
"""
chart_tooltip_new = """        const batteryEntry = entries.find((entry) => entry.seriesName === 'androidklipper-battery')
        if (batteryEntry && typeof batteryEntry.value === 'object' && batteryEntry.value !== null) {
            const batteryValue = (batteryEntry.value as Record<string, number | null>)['androidklipper-battery']
            if (typeof batteryValue === 'number') {
                outputRows += '<div class=\"row\">'
                outputRows += `<div class=\"col-auto py-0\">${batteryEntry.marker}<span class='ml-2'>Android Battery:</span></div>`
                outputRows += `<div class=\"col text-right py-0 font-weight-bold\">${(batteryValue * 100).toFixed(0)}%</div>`
                outputRows += '</div>'
            }
        }

        if (!outputRows) return ''

        const theme = this.$vuetify.theme.dark ? 'theme-dark' : ''
"""
replace_once(chart_path, chart_tooltip_old, chart_tooltip_new, "battery tooltip")

actions_source_old = """            commit('setInitSource', tempDataset)
"""
actions_source_new = """            tempDataset.forEach((entry) => {
                entry['androidklipper-battery'] = null
            })

            commit('setInitSource', tempDataset)
"""
replace_once(actions_path, actions_source_old, actions_source_new, "battery history source")

actions_series_old = """            commit('setInitSeries', series)
"""
actions_series_new = """            const batteryColor = colorArray[colorNumber % colorArray.length] ?? '#9C27B0'
            series.push({
                id: series.length + 1,
                color: batteryColor,
                type: 'line',
                name: 'androidklipper-battery',
                encode: { x: 'date', y: 'androidklipper-battery' },
                animation: false,
                yAxisIndex: 1,
                lineStyle: { color: batteryColor, width: 2, opacity: 0.9 },
                showSymbol: false,
                emphasis: { lineStyle: { color: batteryColor, width: 2, opacity: 0.9 } },
            })

            commit('setInitSeries', series)
"""
replace_once(actions_path, actions_series_old, actions_series_new, "battery chart series")

actions_fetch_old = """            commit('addToSource', {
                data: data,
"""
actions_fetch_new = """            try {
                const response = await fetch('/androidklipper/battery', { cache: 'no-store' })
                if (response.ok) {
                    const battery = (await response.json()) as { level?: number }
                    if (typeof battery.level === 'number' && Number.isFinite(battery.level)) {
                        data['androidklipper-battery'] = Math.max(0, Math.min(1, battery.level / 100))
                    }
                }
            } catch {
                // Android battery telemetry is optional; never disturb normal Mainsail updates.
            }

            commit('addToSource', {
                data: data,
"""
replace_once(actions_path, actions_fetch_old, actions_fetch_new, "battery polling")

getters_old = """                return legends[key] === true && (key.endsWith('-power') || key.endsWith('-speed'))
"""
getters_new = """                return (
                    legends[key] === true &&
                    (key.endsWith('-power') || key.endsWith('-speed') || key === 'androidklipper-battery')
                )
"""
replace_once(getters_path, getters_old, getters_new, "percent-axis visibility")