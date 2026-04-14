package com.rtm.rain

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state plus retry logic. On refresh we first verify the phone is on the
 * expected home WiFi (see [WifiCheck]); if not, we skip the HTTP call
 * entirely and set [wifiNotice] explaining why. Otherwise we make up to
 * MAX_ATTEMPTS HTTP calls (each with its own 5s timeout in RainApi), with
 * a short RETRY_DELAY_MS pause between failed attempts, and surface the
 * attempt number to the UI via [attempt].
 */
data class UiState(
    val totals: RainTotals? = null,
    val shownFetchedAtMs: Long? = null, // when the currently-displayed data was fetched
    val fromCache: Boolean = false,     // true if those totals came from disk, not this session
    val loading: Boolean = false,
    val attempt: Int = 0,               // 1..MAX_ATTEMPTS while loading, 0 otherwise
    val maxAttempts: Int = MAX_ATTEMPTS,
    val lastError: String? = null,
    // Non-null when refresh skipped the fetch because we're not on the
    // expected WiFi. The cached totals (if any) stay on screen.
    val wifiNotice: String? = null,
)

const val MAX_ATTEMPTS = 10
private const val RETRY_DELAY_MS = 750L

class RainViewModel(
    private val cache: RainCache,
    private val appContext: Context,
) : ViewModel() {

    private val stateFlow = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = stateFlow.asStateFlow()

    private var fetchJob: Job? = null

    init {
        // Show any cached values instantly; then try to refresh in the
        // background so the user isn't staring at a blank screen on launch.
        cache.load()?.let { entry ->
            stateFlow.value = stateFlow.value.copy(
                totals = entry.totals,
                shownFetchedAtMs = entry.fetchedAtMs,
                fromCache = true,
            )
        }
        refresh()
    }

    fun refresh() {
        if (fetchJob?.isActive == true) return

        val wifi = WifiCheck.check(appContext)
        if (wifi !is WifiCheck.Result.OnTarget) {
            // Don't even try the HTTP call — surface the reason and leave
            // any cached totals on screen.
            stateFlow.value = stateFlow.value.copy(
                loading = false,
                attempt = 0,
                lastError = null,
                wifiNotice = describeWifi(wifi),
            )
            return
        }

        fetchJob = viewModelScope.launch {
            stateFlow.value = stateFlow.value.copy(
                loading = true,
                attempt = 0,
                lastError = null,
                wifiNotice = null,
            )
            var lastErr: String? = null
            for (i in 1..MAX_ATTEMPTS) {
                stateFlow.value = stateFlow.value.copy(attempt = i, lastError = lastErr)
                try {
                    val fresh = RainApi.fetch()
                    cache.save(fresh)
                    stateFlow.value = UiState(
                        totals = fresh,
                        shownFetchedAtMs = System.currentTimeMillis(),
                        fromCache = false,
                        loading = false,
                        attempt = 0,
                        lastError = null,
                        wifiNotice = null,
                    )
                    return@launch
                } catch (e: Exception) {
                    lastErr = e.message ?: e.javaClass.simpleName
                    if (i < MAX_ATTEMPTS) delay(RETRY_DELAY_MS)
                }
            }
            // All attempts failed; keep whatever totals were already on screen
            // (likely cached) and surface the error.
            stateFlow.value = stateFlow.value.copy(
                loading = false,
                attempt = 0,
                lastError = lastErr,
            )
        }
    }

    private fun describeWifi(r: WifiCheck.Result): String = when (r) {
        WifiCheck.Result.OnTarget ->
            ""  // not reached — only called on non-target results
        is WifiCheck.Result.OnOther ->
            "Not on ${WifiCheck.TARGET_SSID} — connected to \u201C${r.ssid}\u201D"
        WifiCheck.Result.NotOnWifi ->
            "Not connected to WiFi — won't contact the RPi"
        WifiCheck.Result.Unknown ->
            "Can't read WiFi SSID — grant location permission and enable " +
                "location services, then tap Refresh"
    }

    companion object {
        fun factory(ctx: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RainViewModel(
                        RainCache(ctx.applicationContext),
                        ctx.applicationContext,
                    ) as T
            }
    }
}
