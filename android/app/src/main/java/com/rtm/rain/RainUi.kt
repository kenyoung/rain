package com.rtm.rain

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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            RainCard("Last 24 hours",            t.last24hMm,   t.last24hIn)
            RainCard("Last 7 days",              t.lastWeekMm,  t.lastWeekIn)
            RainCard("Last month (30.437 days)", t.lastMonthMm, t.lastMonthIn)
            RainCard("Last year (365.25 days)",  t.lastYearMm,  t.lastYearIn)
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
            val ageMs = System.currentTimeMillis() - ts
            val label = if (s.fromCache) {
                "Showing cached data — fetched ${formatAge(ageMs)} ago"
            } else {
                "Live data — fetched ${formatAge(ageMs)} ago"
            }
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                "(${formatWhen(ts)})",
                style = MaterialTheme.typography.bodySmall,
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
private fun RainCard(title: String, mm: Double, inches: Double) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                String.format(Locale.US, "%.3f in", inches),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                String.format(Locale.US, "%.2f mm", mm),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

private fun formatAge(ms: Long): String {
    val sec = ms / 1000L
    return when {
        sec < 60     -> "${sec}s"
        sec < 3600   -> "${sec / 60}m"
        sec < 86400  -> "${sec / 3600}h ${(sec % 3600) / 60}m"
        else         -> "${sec / 86400}d ${(sec % 86400) / 3600}h"
    }
}

private fun formatWhen(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))
