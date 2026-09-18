package com.betalgezia.omnivpn.vpn

import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface as JNetworkInterface
import javax.inject.Inject

class AndroidPlatformInterface @Inject constructor(
    private val vpnService: VpnService,
    private val onTunEstablished: (ParcelFileDescriptor) -> Unit
) : PlatformInterface {

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        check(vpnService.protect(fd)) { "VpnService.protect($fd) failed" }
    }

    override fun openTun(options: TunOptions): Int {
        check(VpnService.prepare(vpnService) == null) {
            "Android VPN permission is missing"
        }

        val builder = vpnService.Builder()
            .setSession("OmniVPN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        addAddresses(builder, options)

        if (options.autoRoute) {
            addDns(builder, options)
            addRoutes(builder, options)
            addPackages(builder, options)
        }

        val pfd = builder.establish()
            ?: error("VpnService.Builder.establish() returned null")

        onTunEstablished(pfd)
        return pfd.fd
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return ConnectionOwner().apply { userId = Process.INVALID_UID }
        }

        val connectivity =
            vpnService.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager

        val uid = runCatching {
            connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort)
            )
        }.getOrDefault(Process.INVALID_UID)

        if (uid == Process.INVALID_UID) {
            return ConnectionOwner().apply { userId = Process.INVALID_UID }
        }

        val packages = runCatching {
            vpnService.packageManager.getPackagesForUid(uid)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())

        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull().orEmpty()
            setAndroidPackageNames(StringArray(packages.iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = runCatching { buildInterfaces() }
            .getOrElse {
                android.util.Log.w(TAG, "getInterfaces failed: " + it.message)
                emptyList()
            }

        return object : NetworkInterfaceIterator {
            private val iterator = interfaces.iterator()

            override fun hasNext(): Boolean = iterator.hasNext()

            override fun next(): NetworkInterface =
                if (iterator.hasNext()) iterator.next() else NetworkInterface()
        }
    }

    private fun buildInterfaces(): List<NetworkInterface> {
        val connectivity =
            vpnService.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        val systemInterfaces = JNetworkInterface.getNetworkInterfaces().toList()

        return connectivity.allNetworks.mapNotNull { network ->
            val lp = connectivity.getLinkProperties(network) ?: return@mapNotNull null
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            val nativeInterface =
                systemInterfaces.find { it.name == lp.interfaceName }
                    ?: return@mapNotNull null

            NetworkInterface().apply {
                name = lp.interfaceName
                dnsServer = StringArray(
                    lp.dnsServers.mapNotNull { it.hostAddress }.iterator()
                )
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                        Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                        Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                index = nativeInterface.index
                mtu = nativeInterface.mtu
                addresses = StringArray(
                    nativeInterface.interfaceAddresses.map { it.toPrefix() }.iterator()
                )
                var flags = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (nativeInterface.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                if (nativeInterface.isPointToPoint) flags = flags or OsConstants.IFF_POINTOPOINT
                if (nativeInterface.supportsMulticast()) flags = flags or OsConstants.IFF_MULTICAST
                this.flags = flags
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
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
    ): ShellSession = throw UnsupportedOperationException(
        "Android shell integration is not enabled"
    )

    override fun lookupUser(username: String): PlatformUser = PlatformUser()

    override fun lookupSFTPServer(): String = ""

    override fun readSystemSSHHostKey(): String = ""

    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions): BridgeSession =
        throw UnsupportedOperationException("Android platform bridge is not enabled")

    private fun addAddresses(builder: VpnService.Builder, options: TunOptions) {
        val inet4 = options.inet4Address
        while (inet4.hasNext()) {
            val address = inet4.next()
            builder.addAddress(address.address(), address.prefix())
        }

        val inet6 = options.inet6Address
        while (inet6.hasNext()) {
            val address = inet6.next()
            builder.addAddress(address.address(), address.prefix())
        }
    }

    private fun addDns(builder: VpnService.Builder, options: TunOptions) {
        val dns = options.dnsServerAddress
        while (dns.hasNext()) {
            val address = dns.next()
            if (address.isNotBlank()) builder.addDnsServer(address)
        }
    }

    private fun addRoutes(builder: VpnService.Builder, options: TunOptions) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val r4 = options.inet4RouteAddress
            if (r4.hasNext()) {
                while (r4.hasNext()) builder.addRoute(r4.next().toIpPrefix())
            } else if (options.inet4Address.hasNext()) {
                builder.addRoute("0.0.0.0", 0)
            }

            val r6 = options.inet6RouteAddress
            if (r6.hasNext()) {
                while (r6.hasNext()) builder.addRoute(r6.next().toIpPrefix())
            } else if (options.inet6Address.hasNext()) {
                builder.addRoute("::", 0)
            }

            val x4 = options.inet4RouteExcludeAddress
            while (x4.hasNext()) builder.excludeRoute(x4.next().toIpPrefix())

            val x6 = options.inet6RouteExcludeAddress
            while (x6.hasNext()) builder.excludeRoute(x6.next().toIpPrefix())
        } else {
            val r4 = options.inet4RouteRange
            while (r4.hasNext()) {
                val address = r4.next()
                builder.addRoute(address.address(), address.prefix())
            }

            val r6 = options.inet6RouteRange
            while (r6.hasNext()) {
                val address = r6.next()
                builder.addRoute(address.address(), address.prefix())
            }
        }
    }

    private fun addPackages(builder: VpnService.Builder, options: TunOptions) {
        val include = options.includePackage
        while (include.hasNext()) {
            runCatching { builder.addAllowedApplication(include.next()) }
        }

        val exclude = options.excludePackage
        while (exclude.hasNext()) {
            runCatching { builder.addDisallowedApplication(exclude.next()) }
        }
    }

    private class StringArray(
        private val iterator: Iterator<String>
    ) : StringIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = if (iterator.hasNext()) iterator.next() else ""
        override fun len(): Int = 0
    }

    companion object {
        private const val TAG = "AndroidPlatformInterface"
    }
}

private fun InterfaceAddress.toPrefix(): String {
    val host = if (address is Inet6Address) {
        Inet6Address.getByAddress(address.address).hostAddress
    } else {
        address.hostAddress
    }
    return host + "/" + networkPrefixLength
}

private fun RoutePrefix.toIpPrefix(): String =
    address() + "/" + prefix()
