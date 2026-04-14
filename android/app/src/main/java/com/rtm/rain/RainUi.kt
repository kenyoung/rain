package com.rtm.rain

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

@Composable
fun RainScreen(vm: RainViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
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
            RainCard("Last 24 hours", t.last24hIn,  t.last24hSeriesIn,  ticksLast24h(nowMs))
            RainCard("Last 7 days",   t.lastWeekIn, t.lastWeekSeriesIn, ticksLastWeek(nowMs))
            RainCard("Last month",    t.lastMonthIn, t.lastMonthSeriesIn, ticksLastMonth(nowMs))
            RainCard("Last year",     t.lastYearIn,  t.lastYearSeriesIn,  ticksLastYear(nowMs))
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
    xTicks: List<XTick>,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                String.format(Locale.US, "%s — %.3f in", title, totalIn),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            RainBarChart(bins = bins, xTicks = xTicks)
        }
    }
}

/** Position of a tick label along the x-axis, as a fraction of full width. */
private data class XTick(val fractionFromLeft: Float, val text: String)

@Composable
private fun RainBarChart(
    bins: List<Double>,
    xTicks: List<XTick>,
) {
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline
    val labelStyle = MaterialTheme.typography.labelSmall
        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(144.dp),
    ) {
        val xLabelStripPx = 20.dp.toPx()
        val yLabelStripPx = 36.dp.toPx()
        val tickLengthPx = 4.dp.toPx()
        val chartHeight = size.height - xLabelStripPx
        val chartLeft = yLabelStripPx
        val chartWidth = size.width - yLabelStripPx

        // Compute a "nice" y-axis from the data max so tick values land on
        // round numbers (e.g. 0.00 / 0.25 / 0.50 rather than 0 / 0.17 / 0.34).
        val dataMax = (bins.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
        val yAxis = niceYAxis(dataMax)

        // X-baseline and Y-axis lines.
        drawLine(
            color = axisColor,
            start = Offset(chartLeft, chartHeight),
            end = Offset(chartLeft + chartWidth, chartHeight),
            strokeWidth = 1.5f,
        )
        drawLine(
            color = axisColor,
            start = Offset(chartLeft, 0f),
            end = Offset(chartLeft, chartHeight),
            strokeWidth = 1.5f,
        )

        // Bars — scale against the y-axis top (not the raw data max) so the
        // tallest bar sits just below the top tick rather than flush with it.
        val binCount = bins.size
        if (binCount > 0 && yAxis.max > 0.0) {
            val binSpace = chartWidth / binCount
            val barWidth = binSpace * 0.8f
            val barPad = (binSpace - barWidth) / 2f
            bins.forEachIndexed { i, v ->
                if (v > 0.0) {
                    val h = (v / yAxis.max * chartHeight).toFloat()
                    val x = chartLeft + i * binSpace + barPad
                    drawRect(
                        color = barColor,
                        topLeft = Offset(x, chartHeight - h),
                        size = Size(barWidth, h),
                    )
                }
            }
        }

        // Y-tick marks and labels (leftward ticks, right-aligned labels).
        yAxis.ticks.forEach { value ->
            val frac = (value / yAxis.max).toFloat()
            val y = chartHeight - frac * chartHeight

            drawLine(
                color = axisColor,
                start = Offset(chartLeft - tickLengthPx, y),
                end = Offset(chartLeft, y),
                strokeWidth = 1.5f,
            )

            val text = formatYValue(value, yAxis.decimals)
            val layout = textMeasurer.measure(text, style = labelStyle)
            val labelX = chartLeft - tickLengthPx - 2.dp.toPx() - layout.size.width
            val labelY = (y - layout.size.height / 2f)
                .coerceIn(0f, chartHeight - layout.size.height.toFloat())
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(labelX.coerceAtLeast(0f), labelY),
            )
        }

        // X-tick marks and labels.
        xTicks.forEach { tick ->
            val layout = textMeasurer.measure(tick.text, style = labelStyle)
            val tickX = chartLeft + tick.fractionFromLeft * chartWidth

            drawLine(
                color = axisColor,
                start = Offset(tickX, chartHeight),
                end = Offset(tickX, chartHeight + tickLengthPx),
                strokeWidth = 1.5f,
            )

            val rawLabelX = tickX - layout.size.width / 2f
            val labelX = rawLabelX.coerceIn(
                chartLeft,
                chartLeft + chartWidth - layout.size.width.toFloat(),
            )
            val labelY = chartHeight + tickLengthPx +
                (xLabelStripPx - tickLengthPx - layout.size.height) / 2f
            drawText(textLayoutResult = layout, topLeft = Offset(labelX, labelY))
        }
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
    val decimals = when {
        step >= 1.0  -> 0
        step >= 0.1  -> 2   // cover 0.25, 0.5
        step >= 0.01 -> 3   // cover 0.025, 0.05
        else         -> 4
    }
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
    val fmt = SimpleDateFormat("HH:mm", Locale.US)

    // Place a tick at every whole clock hour where hour-of-day % 6 == 0
    // (i.e. 00:00, 06:00, 12:00, 18:00) falling within the 24 h window.
    // This yields 4 or 5 ticks depending on the current time, always
    // with :00 minutes.
    val cal = Calendar.getInstance().apply {
        timeInMillis = startMs
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        // Round up to the next whole hour.
        if (timeInMillis < startMs) add(Calendar.HOUR_OF_DAY, 1)
    }

    val ticks = mutableListOf<XTick>()
    while (cal.timeInMillis <= nowMs) {
        if (cal.get(Calendar.HOUR_OF_DAY) % 6 == 0) {
            val frac = (cal.timeInMillis - startMs).toFloat() / spanMs
            ticks.add(XTick(frac, fmt.format(cal.time)))
        }
        cal.add(Calendar.HOUR_OF_DAY, 1)
    }
    return ticks
}

private fun ticksLastWeek(nowMs: Long): List<XTick> {
    val dayMs = 86400_000L
    val fmt = SimpleDateFormat("EEE", Locale.US)
    // 8 ticks, one per day boundary.
    return (0..7).map { i ->
        val frac = i / 7f
        val t = nowMs - 7 * dayMs + i * dayMs
        XTick(frac, fmt.format(Date(t)))
    }
}

private fun ticksLastMonth(nowMs: Long): List<XTick> {
    val dayMs = 86400_000L
    val fmt = SimpleDateFormat("d", Locale.US)
    // 7 ticks every 5 days over 30 days.
    return (0..6).map { i ->
        val frac = i / 6f
        val t = nowMs - 30 * dayMs + i * 5 * dayMs
        XTick(frac, fmt.format(Date(t)))
    }
}

private fun ticksLastYear(nowMs: Long): List<XTick> {
    val dayMs = 86400_000L
    val fmt = SimpleDateFormat("MMM", Locale.US)
    // 7 ticks every 60 days over 360 days (chart spans 364).
    return (0..6).map { i ->
        val frac = i / 6f
        val t = nowMs - 360 * dayMs + i * 60 * dayMs
        XTick(frac, fmt.format(Date(t)))
    }
}

private fun formatWhen(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))
