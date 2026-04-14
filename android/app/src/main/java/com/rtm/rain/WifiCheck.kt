package com.rtm.rain

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager

/**
 * Inspects the phone's current WiFi connection so the ViewModel can decide
 * whether to attempt the /rain HTTP call. Talking to a generic gateway on
 * some other network with the same IP is pointless and slow (ten retries ×
 * five-second timeout = ~50 seconds of wasted time).
 *
 * Reading the current SSID on Android 10+ requires ACCESS_FINE_LOCATION
 * plus the system-level location service to be enabled. On Android 12+
 * (API 31+) the `NetworkCapabilities.getTransportInfo()` path delivers a
 * *redacted* WifiInfo (SSID = `<unknown ssid>`) even with permission — the
 * un-redacted version is only delivered inside a registered
 * `NetworkCallback`. To avoid the callback dance we fall back to the
 * deprecated-but-still-functional `WifiManager.getConnectionInfo()`,
 * which continues to return the real SSID when the caller has the
 * location permission.
 */
object WifiCheck {
    const val TARGET_SSID = "Young 2.4"

    private const val UNKNOWN_SSID_SENTINEL = "<unknown ssid>"

    sealed class Result {
        /** Connected to the expected SSID. Safe to fetch. */
        object OnTarget : Result()
        /** Connected to WiFi, but to a different network. */
        data class OnOther(val ssid: String) : Result()
        /** No WiFi (cellular or no connectivity). */
        object NotOnWifi : Result()
        /** Can't read the SSID — permission denied, location off, etc. */
        object Unknown : Result()
    }

    fun check(ctx: Context): Result {
        val app = ctx.applicationContext
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return Result.NotOnWifi
        val caps = cm.getNetworkCapabilities(network) ?: return Result.NotOnWifi
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return Result.NotOnWifi
        }

        // Try the modern path first; if it comes back redacted, fall back to
        // the WifiManager path which still yields the real SSID for an app
        // holding ACCESS_FINE_LOCATION.
        val ssid = ssidFromCaps(caps) ?: ssidFromWifiManager(app)
            ?: return Result.Unknown

        return if (ssid == TARGET_SSID) Result.OnTarget else Result.OnOther(ssid)
    }

    private fun ssidFromCaps(caps: NetworkCapabilities): String? {
        val wi = caps.transportInfo as? WifiInfo ?: return null
        return cleanSsid(wi.ssid)
    }

    @Suppress("DEPRECATION")
    private fun ssidFromWifiManager(ctx: Context): String? {
        val mgr = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return cleanSsid(mgr.connectionInfo?.ssid)
    }

    private fun cleanSsid(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val trimmed = raw.trim('"')
        if (trimmed.isEmpty() || trimmed == UNKNOWN_SSID_SENTINEL) return null
        return trimmed
    }
}
