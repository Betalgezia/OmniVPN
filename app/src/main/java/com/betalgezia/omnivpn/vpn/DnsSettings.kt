package com.betalgezia.omnivpn.vpn

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's DNS choice for AmneziaWG/WARP (see [DnsEndpoint] and
 * [SingBoxConfigBuilder]). Nothing here is a secret, so plain
 * SharedPreferences is enough - same reasoning as [WarpSettings].
 *
 * Proxy protocols (VLESS/Trojan/Hysteria2/…) never read this: their
 * destination domains are resolved by the proxy server itself (fakeip +
 * sniff), so a local resolver choice cannot affect them.
 */
@Singleton
class DnsSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("omnivpn_dns", Context.MODE_PRIVATE)

    /** [DnsCatalog.DEFAULT_ID], a preset id from [DnsCatalog], or [DnsCatalog.CUSTOM_ID]. */
    var selectedId: String
        get() = prefs.getString(KEY_SELECTED, DnsCatalog.DEFAULT_ID).orEmpty()
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    /** What the user typed (normalised), kept even while a preset is selected. */
    var customText: String
        get() = prefs.getString(KEY_CUSTOM, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CUSTOM, value).apply()

    /**
     * The resolver to build into the next endpointMode config; null means the
     * built-in default (UDP 1.1.1.1 through the tunnel). An unknown preset id
     * or an unparsable custom value falls back to that default rather than
     * breaking the connection.
     */
    fun endpoint(): DnsEndpoint? = when (selectedId) {
        DnsCatalog.DEFAULT_ID -> null
        DnsCatalog.CUSTOM_ID -> parseDnsEndpoint(customText)
        else -> DnsCatalog.byId(selectedId)?.endpoint
    }

    private companion object {
        const val KEY_SELECTED = "selected"
        const val KEY_CUSTOM = "custom"
    }
}
