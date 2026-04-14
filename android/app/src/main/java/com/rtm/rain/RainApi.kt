package com.rtm.rain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Single HTTP call to the RPi's weewxRainApi service. One call = one attempt
 * that must complete within RAIN_TIMEOUT_SEC. Retry logic lives in the
 * ViewModel so the UI can display the attempt count.
 */
object RainApi {
    const val RAIN_URL = "http://192.168.1.102:8765/rain"
    const val RAIN_TIMEOUT_SEC = 5L

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(RAIN_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(RAIN_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(RAIN_TIMEOUT_SEC, TimeUnit.SECONDS)
        .callTimeout(RAIN_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // we do our own retry loop
        .build()

    suspend fun fetch(): RainTotals = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(RAIN_URL).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("empty body")
            parseJson(body)
        }
    }

    fun parseJson(json: String): RainTotals {
        val root = JSONObject(json)
        val totals = root.getJSONObject("totals")
        val series = root.getJSONObject("series")

        fun inches(name: String) = totals.getJSONObject(name).getDouble("inches")
        fun bins(name: String): List<Double> =
            series.getJSONObject(name).getJSONArray("binsIn").toDoubleList()
        fun binSec(name: String): Long =
            series.getJSONObject(name).getLong("binSec")

        return RainTotals(
            last24hIn        = inches("last24h"),
            last24hSeriesIn  = bins("last24h"),
            last24hBinSec    = binSec("last24h"),

            lastWeekIn       = inches("lastWeek"),
            lastWeekSeriesIn = bins("lastWeek"),
            lastWeekBinSec   = binSec("lastWeek"),

            lastMonthIn       = inches("lastMonth"),
            lastMonthSeriesIn = bins("lastMonth"),
            lastMonthBinSec   = binSec("lastMonth"),

            lastYearIn        = inches("lastYear"),
            lastYearSeriesIn  = bins("lastYear"),
            lastYearBinSec    = binSec("lastYear"),

            generatedAt   = root.getLong("generatedAt"),
            latestArchive =
                if (root.isNull("latestArchive")) null else root.optLong("latestArchive"),
        )
    }

    private fun JSONArray.toDoubleList(): List<Double> =
        List(length()) { getDouble(it) }
}
