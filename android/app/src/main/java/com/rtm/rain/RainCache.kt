package com.rtm.rain

import android.content.Context
import org.json.JSONObject

/**
 * Persists the most recent successful fetch so the app can show something
 * useful when the RPi is unreachable. Backed by SharedPreferences — a single
 * JSON blob plus the wall-clock time we saved it.
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
            val t = JSONObject()
            fun w(name: String, mm: Double, inches: Double) {
                t.put(name, JSONObject().put("mm", mm).put("inches", inches))
            }
            w("last24h",   totals.last24hMm,   totals.last24hIn)
            w("lastWeek",  totals.lastWeekMm,  totals.lastWeekIn)
            w("lastMonth", totals.lastMonthMm, totals.lastMonthIn)
            w("lastYear",  totals.lastYearMm,  totals.lastYearIn)
            put("totals", t)
        }
        prefs.edit()
            .putString(KEY_PAYLOAD, obj.toString())
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .apply()
    }

    companion object {
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_FETCHED_AT = "fetchedAtMs"
    }
}
