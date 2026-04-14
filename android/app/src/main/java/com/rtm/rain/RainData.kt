package com.rtm.rain

/**
 * One bundle of rainfall totals fetched from the RPi. All values are stored
 * in both millimetres and inches (the server provides both), so the UI does
 * no unit conversion.
 */
data class RainTotals(
    val last24hMm: Double,
    val last24hIn: Double,
    val lastWeekMm: Double,
    val lastWeekIn: Double,
    val lastMonthMm: Double,
    val lastMonthIn: Double,
    val lastYearMm: Double,
    val lastYearIn: Double,
    // unix seconds when the RPi built the payload
    val generatedAt: Long,
    // unix seconds of the newest archive record on the RPi (null if DB empty)
    val latestArchive: Long?,
)
