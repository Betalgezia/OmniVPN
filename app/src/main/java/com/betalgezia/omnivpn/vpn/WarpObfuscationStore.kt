package com.betalgezia.omnivpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Persists a tuned [AwgObfuscation] profile *per network*, because the DPI
 * that a given junk/decoy shape has to get past is a property of the network
 * (a home ISP vs. a mobile carrier), so a profile found on one network is not
 * necessarily the right one on another. If the current network has no saved
 * profile, WARP falls back to [WarpAccount.DEFAULT_OBFUSCATION].
 *
 * Network key: mobile networks key on MCC+MNC (the carrier), which needs no
 * permission. Wi-Fi would ideally key on SSID, but reading the SSID needs the
 * location permission on Android 8.1+, which this app deliberately doesn't
 * request - so all Wi-Fi shares one "wifi" key. That's a coarser bucket than
 * ideal (two different Wi-Fi networks share a profile), but it avoids a
 * location-permission prompt for a convenience feature; re-running the tuner
 * on a new Wi-Fi simply overwrites the shared profile.
 *
 * Dual construction (plain + @Inject) mirrors WarpStorage/WarpSettings:
 * VpnController builds it inline, the ViewModel/tuner take it via Hilt.
 */
class WarpObfuscationStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A permission-free coarse key for the currently active network. */
    fun currentNetworkKey(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return KEY_DEFAULT
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return KEY_DEFAULT
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val op = runCatching {
                    context.getSystemService(TelephonyManager::class.java)?.networkOperator
                }.getOrNull()
                if (op.isNullOrBlank()) "cellular" else "mcc-mnc:$op"
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> KEY_DEFAULT
        }
    }

    fun load(networkKey: String = currentNetworkKey()): AwgObfuscation? =
        prefs.getString(key(networkKey), null)?.let { AwgObfuscation.fromJson(it) }

    /** The saved profile for the current network, or the built-in default. */
    fun currentOrDefault(): AwgObfuscation = load() ?: WarpAccount.DEFAULT_OBFUSCATION

    fun save(networkKey: String, obf: AwgObfuscation) {
        prefs.edit().putString(key(networkKey), obf.toJson()).apply()
    }

    fun clear(networkKey: String = currentNetworkKey()) {
        prefs.edit().remove(key(networkKey)).apply()
    }

    private fun key(networkKey: String) = "obf:$networkKey"

    companion object {
        private const val PREFS = "omnivpn_obfuscation"
        private const val KEY_DEFAULT = "default"
    }
}
