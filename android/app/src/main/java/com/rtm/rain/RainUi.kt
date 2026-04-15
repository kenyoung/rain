package com.rtm.rain

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RainScreen(vm: RainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    PullToRefreshBox(
        isRefreshing = s.loading,
        onRefresh = vm::refresh,
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Rainfall", style = MaterialTheme.typography.headlineMedium)

            StatusRow(s, onRefresh = vm::refresh)

            val t = s.totals
            if (t == null) {
                Text(
                    if (s.loading) "Contacting RPi…"
                    else "No data yet. Tap Refresh to try again.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                // generatedAt is unix seconds on the wire; convert to ms for the
                // tick helpers, which format with SimpleDateFormat.
                val nowMs = t.generatedAt * 1000L
                RainCard("Last 24 hours", t.last24hIn,  t.last24hSeriesIn,  t.last24hBinSec,  nowMs, ticksLast24h(nowMs))
                RainCard("Last 7 days",   t.lastWeekIn, t.lastWeekSeriesIn, t.lastWeekBinSec, nowMs, ticksLastWeek(nowMs))
                RainCard("Last month",    t.lastMonthIn, t.lastMonthSeriesIn, t.lastMonthBinSec, nowMs, ticksLastMonth(nowMs))
                RainCard("Last year",     t.lastYearIn,  t.lastYearSeriesIn,  t.lastYearBinSec,  nowMs, ticksLastYear(nowMs))
            }
        }
    }
}

@Composable
private fun StatusRow(s: UiState, onRefresh: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = onRefresh, enabled = !s.loading) { Text("Refresh") }
            if (s.loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                Text(
                    "Attempt ${s.attempt} of ${s.maxAttempts}…",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        s.shownFetchedAtMs?.let { ts ->
            val label = if (s.fromCache) {
                "Showing cached data — fetched ${formatWhen(ts)}"
            } else {
                "Live data — fetched ${formatWhen(ts)}"
            }
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
        s.wifiNotice?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        s.lastError?.let {
            Text(
                "Last error: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RainCard(
    title: String,
    totalIn: Double,
    bins: List<Double>,
    binSec: Long,
    nowMs: Long,
    xTicks: List<XTick>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                String.format(Locale.US, "%s — %.2f in", title, totalIn),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            RainBarChart(
                bins = bins,
                binSec = binSec,
                nowMs = nowMs,
                xTicks = xTicks,
            )
        }
    }
}

/** Position of a tick label along the x-axis, as a fraction of full width. */
private data class XTick(val fractionFromLeft: Float, val text: String)

// Width reserved on the left for y-axis labels, and height reserved on the
// bottom for x-axis labels. Shared between the Canvas DrawScope (which
// draws axes at these offsets) and the pointerInput tap handler (which
// needs the same offsets to map taps back to bins).
private val Y_LABEL_STRIP: Dp = 36.dp
private val X_LABEL_STRIP: Dp = 20.dp

private data class ChartLayout(
    val chartLeft: Float,
    val chartWidth: Float,
    val chartHeight: Float,
    val binSpace: Float,
)

private fun Density.chartLayout(width: Float, height: Float, binCount: Int): ChartLayout {
    val left = Y_LABEL_STRIP.toPx()
    val w = width - left
    return ChartLayout(
        chartLeft = left,
        chartWidth = w,
        chartHeight = height - X_LABEL_STRIP.toPx(),
        binSpace = if (binCount > 0) w / binCount else 0f,
    )
}

// SimpleDateFormat is expensive to construct and not thread-safe, but
// every caller below runs on the Compose main thread, so one shared
// instance per pattern is safe.
private val fmtHhMm     = SimpleDateFormat("HH:mm",       Locale.US)
private val fmtEee      = SimpleDateFormat("EEE",         Locale.US)
private val fmtD        = SimpleDateFormat("d",           Locale.US)
private val fmtMmm      = SimpleDateFormat("MMM",         Locale.US)
private val fmtEeeHhMm  = SimpleDateFormat("EEE HH:mm",   Locale.US)
private val fmtEeeMmmD  = SimpleDateFormat("EEE MMM d",   Locale.US)
private val fmtMmmD     = SimpleDateFormat("MMM d",       Locale.US)
private val fmtWhen     = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

@Composable
private fun RainBarChart(
    bins: List<Double>,
    binSec: Long,
    nowMs: Long,
    xTicks: List<XTick>,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val selectedBarColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.outline
    val labelStyle = MaterialTheme.typography.labelSmall
        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val tooltipStyle = MaterialTheme.typography.labelMedium
        .copy(color = MaterialTheme.colorScheme.inverseOnSurface)
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val textMeasurer = rememberTextMeasurer()

    // Reset selection whenever the underlying bins change (e.g. a fresh
    // /rain payload arrives) so a stale selection doesn't get misread.
    var selectedBin by remember(bins, nowMs) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(144.dp)
            .pointerInput(bins.size, binSec, nowMs) {
                detectTapGestures { offset ->
                    val l = chartLayout(size.width.toFloat(), size.height.toFloat(), bins.size)
                    val inChart = offset.x in l.chartLeft..(l.chartLeft + l.chartWidth) &&
                        offset.y in 0f..l.chartHeight
                    if (!inChart || bins.isEmpty()) {
                        selectedBin = null
                        return@detectTapGestures
                    }
                    val idx = ((offset.x - l.chartLeft) / l.binSpace)
                        .toInt().coerceIn(0, bins.size - 1)
                    // Tap a visible bar to select it, or the same bar / an
                    // empty column / any axis strip to dismiss.
                    selectedBin = if (bins[idx] > 0.0 && selectedBin != idx) idx else null
                }
            },
    ) {
        val l = chartLayout(size.width, size.height, bins.size)
        val tickLengthPx = 4.dp.toPx()
        val xLabelStripPx = X_LABEL_STRIP.toPx()

        // "Nice" y-axis whose tick values round to familiar increments
        // (0.00 / 0.25 / 0.50 rather than 0 / 0.17 / 0.34).
        val dataMax = (bins.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
        val yAxis = niceYAxis(dataMax)

        // X-baseline and Y-axis lines.
        drawLine(
            color = axisColor,
            start = Offset(l.chartLeft, l.chartHeight),
            end = Offset(l.chartLeft + l.chartWidth, l.chartHeight),
            strokeWidth = 1.5f,
        )
        drawLine(
            color = axisColor,
            start = Offset(l.chartLeft, 0f),
            end = Offset(l.chartLeft, l.chartHeight),
            strokeWidth = 1.5f,
        )

        // Bars — scaled against the y-axis top (not the raw data max) so
        // the tallest bar sits just below the top tick.
        val barWidth = l.binSpace * 0.8f
        val barPad = (l.binSpace - barWidth) / 2f
        if (bins.isNotEmpty() && yAxis.max > 0.0) {
            bins.forEachIndexed { i, v ->
                if (v > 0.0) {
                    val h = (v / yAxis.max * l.chartHeight).toFloat()
                    val x = l.chartLeft + i * l.binSpace + barPad
                    drawRect(
                        color = if (i == selectedBin) selectedBarColor else barColor,
                        topLeft = Offset(x, l.chartHeight - h),
                        size = Size(barWidth, h),
                    )
                }
            }
        }

        // Y-tick marks (leftward) + labels (right-aligned to the tick).
        yAxis.ticks.forEach { value ->
            val y = l.chartHeight - (value / yAxis.max).toFloat() * l.chartHeight

            drawLine(
                color = axisColor,
                start = Offset(l.chartLeft - tickLengthPx, y),
                end = Offset(l.chartLeft, y),
                strokeWidth = 1.5f,
            )

            val layout = textMeasurer.measure(
                formatYValue(value, yAxis.decimals), style = labelStyle,
            )
            val labelX = l.chartLeft - tickLengthPx - 2.dp.toPx() - layout.size.width
            val labelY = (y - layout.size.height / 2f)
                .coerceIn(0f, l.chartHeight - layout.size.height.toFloat())
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(labelX.coerceAtLeast(0f), labelY),
            )
        }

        // X-tick marks and labels.
        xTicks.forEach { tick ->
            val layout = textMeasurer.measure(tick.text, style = labelStyle)
            val tickX = l.chartLeft + tick.fractionFromLeft * l.chartWidth

            drawLine(
                color = axisColor,
                start = Offset(tickX, l.chartHeight),
                end = Offset(tickX, l.chartHeight + tickLengthPx),
                strokeWidth = 1.5f,
            )

            val labelX = (tickX - layout.size.width / 2f).coerceIn(
                l.chartLeft,
                l.chartLeft + l.chartWidth - layout.size.width.toFloat(),
            )
            val labelY = l.chartHeight + tickLengthPx +
                (xLabelStripPx - tickLengthPx - layout.size.height) / 2f
            drawText(textLayoutResult = layout, topLeft = Offset(labelX, labelY))
        }

        // Tooltip — drawn last so it sits above any tall bar underneath.
        val sel = selectedBin
        if (sel != null && sel in bins.indices) {
            val binStart = nowMs - (bins.size - sel) * binSec * 1000L
            val binEnd = binStart + binSec * 1000L
            val text = String.format(Locale.US, "%.2f in", bins[sel]) +
                "\n" + formatBinRange(binStart, binEnd, binSec)
            val layout = textMeasurer.measure(text, style = tooltipStyle)
            val padPx = 6.dp.toPx()
            val boxW = layout.size.width + padPx * 2
            val boxH = layout.size.height + padPx * 2

            val barCentreX = l.chartLeft + sel * l.binSpace + l.binSpace / 2f
            val boxX = (barCentreX - boxW / 2f)
                .coerceIn(l.chartLeft, l.chartLeft + l.chartWidth - boxW)
            val boxY = 2.dp.toPx()

            drawRect(
                color = tooltipBg,
                topLeft = Offset(boxX, boxY),
                size = Size(boxW, boxH),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(boxX + padPx, boxY + padPx),
            )
        }
    }
}

/** Format a [start, end) time range for a tooltip, scale-appropriate for
 *  the bar width. */
private fun formatBinRange(startMs: Long, endMs: Long, binSec: Long): String {
    val start = Date(startMs)
    return when {
        binSec < 86400L ->
            "${fmtEeeHhMm.format(start)}\u2013${fmtHhMm.format(Date(endMs))}"
        binSec == 86400L ->
            fmtEeeMmmD.format(start)
        // Multi-day bin (week bars on the year chart): inclusive range.
        else ->
            "${fmtMmmD.format(start)}\u2013${fmtMmmD.format(Date(endMs - 1L))}"
    }
}

/** Result of picking a y-axis range: top value, tick positions, and the
 *  number of decimal places appropriate for the chosen step. */
private data class YAxis(
    val max: Double,
    val ticks: List<Double>,
    val decimals: Int,
)

/**
 * Pick a "nice" y-axis for a given data max. Targets three ticks (0, mid,
 * top) with values that round to familiar increments (1, 2, 2.5, 5, 10 × 10^n).
 * If there's no data (or it's all zero), use a small default scale so the
 * axis still renders with readable numbers.
 */
private fun niceYAxis(dataMax: Double): YAxis {
    if (dataMax <= 0.0) {
        return YAxis(max = 0.10, ticks = listOf(0.00, 0.05, 0.10), decimals = 2)
    }
    // Target two intervals (three ticks) -> rough step is half the data max.
    val rough = dataMax / 2.0
    val exp = floor(log10(rough)).toInt()
    val base = 10.0.pow(exp)
    val mantissa = rough / base
    val niceMantissa = when {
        mantissa <= 1.0 -> 1.0
        mantissa <= 2.0 -> 2.0
        mantissa <= 2.5 -> 2.5
        mantissa <= 5.0 -> 5.0
        else -> 10.0
    }
    val step = niceMantissa * base

    val ticks = mutableListOf(0.0)
    var next = step
    while (next < dataMax - 1e-9) {
        ticks.add(next)
        next += step
    }
    ticks.add(next)
    // Cap at 2 decimal places everywhere in the app. For very small
    // steps (e.g. 0.005) this means adjacent ticks may round to the same
    // 2-decimal string — an acceptable tradeoff for the "no more than
    // 2 decimals anywhere" rule.
    val decimals = if (step >= 1.0) 0 else 2
    return YAxis(max = next, ticks = ticks, decimals = decimals)
}

private fun formatYValue(v: Double, decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f", v)

// ---- X-tick helpers ---------------------------------------------------
// Each function returns ticks for a given chart, using `nowMs` (the RPi's
// generatedAt in ms) as the right-edge anchor. Labels are formatted in the
// phone's local timezone using Locale.US for consistent short names.

private fun ticksLast24h(nowMs: Long): List<XTick> {
    val spanMs = 24L * 3600_000L
    val startMs = nowMs - spanMs

    // Place a tick at every whole clock hour where hour-of-day % 6 == 0
    // (i.e. 00:00, 06:00, 12:00, 18:00) falling within the 24 h window.
    // This yields 4 or 5 ticks depending on the current time, always
    // with :00 minutes.
    val cal = Calendar.getInstance().apply {
        timeInMillis = startMs
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis < startMs) add(Calendar.HOUR_OF_DAY, 1)
    }

    val ticks = mutableListOf<XTick>()
    while (cal.timeInMillis <= nowMs) {
        if (cal.get(Calendar.HOUR_OF_DAY) % 6 == 0) {
            val frac = (cal.timeInMillis - startMs).toFloat() / spanMs
            ticks.add(XTick(frac, fmtHhMm.format(cal.time)))
        }
        cal.add(Calendar.HOUR_OF_DAY, 1)
    }
    return ticks
}

private fun ticksLastWeek(nowMs: Long): List<XTick> {
    val spanMs = 7L * 86400_000L
    val startMs = nowMs - spanMs

    // Tick at every local midnight in the 7-day window, so labels line
    // up with the actual day boundaries of the bars underneath.
    val cal = Calendar.getInstance().apply {
        timeInMillis = startMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis < startMs) add(Calendar.DAY_OF_MONTH, 1)
    }

    val ticks = mutableListOf<XTick>()
    while (cal.timeInMillis <= nowMs) {
        val frac = (cal.timeInMillis - startMs).toFloat() / spanMs
        ticks.add(XTick(frac, fmtEee.format(cal.time)))
        cal.add(Calendar.DAY_OF_MONTH, 1)
    }
    return ticks
}

private val MONTH_TICK_DAYS = setOf(1, 5, 10, 15, 20, 25)

private fun ticksLastMonth(nowMs: Long): List<XTick> {
    val spanMs = 30L * 86400_000L
    val startMs = nowMs - spanMs

    // Tick at local midnight whenever day-of-month is in MONTH_TICK_DAYS.
    // Always includes the 1st so month transitions are visible.
    val cal = Calendar.getInstance().apply {
        timeInMillis = startMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (timeInMillis < startMs) add(Calendar.DAY_OF_MONTH, 1)
    }

    val ticks = mutableListOf<XTick>()
    while (cal.timeInMillis <= nowMs) {
        if (cal.get(Calendar.DAY_OF_MONTH) in MONTH_TICK_DAYS) {
            val frac = (cal.timeInMillis - startMs).toFloat() / spanMs
            ticks.add(XTick(frac, fmtD.format(cal.time)))
        }
        cal.add(Calendar.DAY_OF_MONTH, 1)
    }
    return ticks
}

private fun ticksLastYear(nowMs: Long): List<XTick> {
    val dayMs = 86400_000L
    // 7 ticks every 60 days over 360 days (chart spans 364).
    return (0..6).map { i ->
        val frac = i / 6f
        val t = nowMs - 360 * dayMs + i * 60 * dayMs
        XTick(frac, fmtMmm.format(Date(t)))
    }
}

private fun formatWhen(ts: Long): String = fmtWhen.format(Date(ts))
