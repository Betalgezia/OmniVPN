package com.betalgezia.omnivpn.vpn

import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.SystemProxyStatus

/**
 * CommandServerHandler for a liveness-probe sing-box run (see NodeHealthChecker).
 * Its lifecycle is driven entirely by NodeHealthChecker's own start()/stop() calls,
 * not by anything libbox itself asks for, so every callback here is a safe no-op.
 */
class ProbeCommandServerHandler : CommandServerHandler {
    override fun connectSSHAgent(): Int =
        throw UnsupportedOperationException("Probe sessions do not support SSH agent forwarding")

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun serviceReload() = Unit
    override fun serviceStop() = Unit
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String) {
        runCatching { android.util.Log.d(TAG, "[probe] $message") }
    }

    private companion object {
        const val TAG = "ProbeCommandServer"
    }
}
