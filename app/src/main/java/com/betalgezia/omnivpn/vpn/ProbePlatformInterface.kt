package com.betalgezia.omnivpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState

/**
 * PlatformInterface for a liveness-probe sing-box run (see NodeHealthChecker /
 * SingBoxConfigBuilder.buildProbeConfig): no tun inbound, so openTun/protect are
 * never expected to be called, and no auto_detect_interface route setting, so the
 * interface-monitoring/process-owner callbacks are never expected to matter either -
 * both are implemented defensively rather than left unimplemented.
 *
 * Deliberately a separate class from AndroidPlatformInterface rather than a shared
 * base: that class is the one every real VPN connection depends on and has a long
 * history of hard-won fixes (DNS leaks, TUN establishment races, ...) - a probe-only
 * code path has no business risking it, even at the cost of duplicating the handful
 * of trivial no-op overrides below.
 */
class ProbePlatformInterface(context: Context) : PlatformInterface {

    private val connectivity = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    // Real DNS resolution (AndroidLocalDnsResolver only needs a ConnectivityManager,
    // not a VpnService) so domain-hostname nodes still resolve during a probe.
    override fun localDNSTransport(): LocalDNSTransport? = AndroidLocalDnsResolver(connectivity)

    // No tun, so nothing to protect sockets from.
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = false
    override fun autoDetectInterfaceControl(fd: Int) = Unit

    override fun openTun(options: TunOptions): Int =
        throw UnsupportedOperationException("Probe sessions never open a TUN")

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner = ConnectionOwner().apply { userId = Process.INVALID_UID }

    // Not enabling auto_detect_interface in the probe config, so libbox has no
    // reason to register a listener here - a no-op keeps outbound dials on the
    // OS's normal default route instead of pretending to track one.
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun getInterfaces(): NetworkInterfaceIterator = object : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = false
        override fun next(): NetworkInterface = NetworkInterface()
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun clearDNSCache() = Unit
    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) = Unit
    override fun cancelNotification(identifier: String, typeID: Int) = Unit
    override fun startNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) = Unit
    override fun closeNeighborMonitor(listener: io.nekohasekai.libbox.NeighborUpdateListener) = Unit
    override fun registerMyInterface(name: String) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = Unit

    override fun openShellSession(
        user: PlatformUser,
        command: String,
        env: StringIterator,
        termType: String,
        width: Int,
        height: Int
    ): ShellSession = throw UnsupportedOperationException("Probe sessions do not support a shell")

    override fun lookupUser(username: String): PlatformUser = PlatformUser()
    override fun lookupSFTPServer(): String = ""
    override fun readSystemSSHHostKey(): String = ""
    override fun tailscaleHostname(): String = ""
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions): BridgeSession =
        throw UnsupportedOperationException("Probe sessions do not support a bridge")
}
