package com.betalgezia.omnivpn.vpn

sealed interface VpnEvent {
    data object Connecting : VpnEvent
    data object Connected : VpnEvent
    data class Error(val message: String) : VpnEvent
    data object Disconnected : VpnEvent
    data object Revoked : VpnEvent
}
