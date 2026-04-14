package com.rtm.rain

/**
 * One bundle of rainfall data fetched from the RPi. The RPi returns both
 * the per-interval total and a per-bin time series for charting. All
 * rainfall figures are in inches; binsIn[0] is the oldest bin, binsIn[N-1]
 * is the newest (ending at generatedAt).
 */
data class RainTotals(
    val last24hIn: Double,
    val last24hSeriesIn: List<Double>,
    val last24hBinSec: Long,

    val lastWeekIn: Double,
    val lastWeekSeriesIn: List<Double>,
    val lastWeekBinSec: Long,

    val lastMonthIn: Double,
    val lastMonthSeriesIn: List<Double>,
    val lastMonthBinSec: Long,

    val lastYearIn: Double,
    val lastYearSeriesIn: List<Double>,
    val lastYearBinSec: Long,

    // unix seconds when the RPi built the payload
    val generatedAt: Long,
    // unix seconds of the newest archive record on the RPi (null if DB empty)
    val latestArchive: Long?,
)
