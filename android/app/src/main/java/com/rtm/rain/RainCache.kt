package com.rtm.rain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the most recent successful fetch so the app can show something
 * useful when the RPi is unreachable. Backed by SharedPreferences — a
 * single JSON blob (identical shape to what the RPi serves, including
 * per-bin series arrays) plus the wall-clock time we saved it.
 */
class RainCache(ctx: Context) {

    data class Entry(val totals: RainTotals, val fetchedAtMs: Long)

    private val prefs =
        ctx.applicationContext.getSharedPreferences("rainCache", Context.MODE_PRIVATE)

    fun load(): Entry? {
        val json = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val fetchedAtMs = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (fetchedAtMs == 0L) return null
        return try {
            Entry(RainApi.parseJson(json), fetchedAtMs)
        } catch (_: Exception) {
            null
        }
    }

    fun save(totals: RainTotals) {
        val obj = JSONObject().apply {
            put("generatedAt", totals.generatedAt)
            totals.latestArchive?.let { put("latestArchive", it) }

            put("totals", JSONObject().apply {
                putTotal("last24h",   totals.last24hIn)
                putTotal("lastWeek",  totals.lastWeekIn)
                putTotal("lastMonth", totals.lastMonthIn)
                putTotal("lastYear",  totals.lastYearIn)
            })

            put("series", JSONObject().apply {
                putSeries("last24h",   totals.last24hBinSec,   totals.last24hSeriesIn)
                putSeries("lastWeek",  totals.lastWeekBinSec,  totals.lastWeekSeriesIn)
                putSeries("lastMonth", totals.lastMonthBinSec, totals.lastMonthSeriesIn)
                putSeries("lastYear",  totals.lastYearBinSec,  totals.lastYearSeriesIn)
            })
        }
        prefs.edit()
            .putString(KEY_PAYLOAD, obj.toString())
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun JSONObject.putTotal(name: String, inches: Double) {
        put(name, JSONObject()
            .put("inches", inches)
            .put("mm", inches * 25.4))
    }

    private fun JSONObject.putSeries(name: String, binSec: Long, bins: List<Double>) {
        put(name, JSONObject()
            .put("binSec", binSec)
            .put("binsIn", JSONArray(bins)))
    }

    companion object {
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_FETCHED_AT = "fetchedAtMs"
    }
}
