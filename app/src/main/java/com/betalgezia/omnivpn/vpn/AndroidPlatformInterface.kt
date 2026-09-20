package com.betalgezia.omnivpn.vpn

import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.annotation.RequiresApi
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface as JNetworkInterface
import javax.inject.Inject

class AndroidPlatformInterface @Inject constructor(
    private val vpnService: VpnService,
    private val onTunEstablished: (ParcelFileDescriptor) -> Unit
) : PlatformInterface {

    private val monitorLock = Any()

    @Volatile
    private var defaultInterfaceListener: InterfaceUpdateListener? = null

    private var defaultInterfaceCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var monitoredNetwork: android.net.Network? = null

    override fun localDNSTransport(): LocalDNSTransport? = AndroidLocalDnsResolver(vpnService)

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        android.util.Log.i(TAG, "protect(fd=$fd) called")
        check(vpnService.protect(fd)) { "VpnService.protect($fd) failed" }
        android.util.Log.i(TAG, "protect(fd=$fd) succeeded")
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

        android.util.Log.i(TAG, "openTun: TUN established fd=" + pfd.fd + ", mtu=" + options.mtu + ", autoRoute=" + options.autoRoute)
        onTunEstablished(pfd)
        android.util.Log.i(TAG, "openTun: TUN handed to libbox fd=" + pfd.fd)
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
            setAndroidPackageNames(StringArray(packages))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        synchronized(monitorLock) {
            unregisterDefaultInterfaceMonitorLocked()
            defaultInterfaceListener = listener

            val connectivity = vpnService.getSystemService(ConnectivityManager::class.java)
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

            // Android's activeNetwork can resolve to the VPN once this
            // VpnService is established. Seed only with a non-VPN network.
            val initialNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivity.activeNetwork?.takeUnless { isVpnNetwork(connectivity, it) }
            } else {
                null
            }
            android.util.Log.i(
                TAG,
                "default network monitor: initial=" +
                    (initialNetwork?.toString() ?: "null") +
                    ", vpn=" +
                    (initialNetwork?.let { isVpnNetwork(connectivity, it) } ?: false)
            )
            if (initialNetwork != null) {
                notifyDefaultInterface(initialNetwork)
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    notifyDefaultInterface(network)
                }

                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    capabilities: NetworkCapabilities
                ) {
                    notifyDefaultInterface(network)
                }

                override fun onLost(network: android.net.Network) {
                    if (monitoredNetwork == network) {
                        monitoredNetwork = null
                        notifyDefaultInterface(null)
                    }
                }
            }

            try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        // Android 12+: receive the best matching underlying
                        // network rather than every INTERNET-capable network.
                        connectivity.registerBestMatchingNetworkCallback(
                            request,
                            callback,
                            android.os.Handler(android.os.Looper.getMainLooper())
                        )
                    }

                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                        // Android 9-11: requestNetwork selects the requested
                        // network instead of exposing every candidate interface.
                        connectivity.requestNetwork(
                            request,
                            callback,
                            android.os.Handler(android.os.Looper.getMainLooper())
                        )
                    }

                    else -> {
                        connectivity.registerDefaultNetworkCallback(callback)
                    }
                }
                defaultInterfaceCallback = callback
            } catch (t: Throwable) {
                defaultInterfaceListener = null
                monitoredNetwork = null
                throw t
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        synchronized(monitorLock) {
            unregisterDefaultInterfaceMonitorLocked()
        }
    }

    private fun unregisterDefaultInterfaceMonitorLocked() {
        val connectivity = vpnService.getSystemService(ConnectivityManager::class.java)
        defaultInterfaceCallback?.let { callback ->
            runCatching { connectivity.unregisterNetworkCallback(callback) }
                .onFailure {
                    android.util.Log.w(TAG, "default interface monitor unregister failed: " + it.message)
                }
        }
        defaultInterfaceCallback = null
        defaultInterfaceListener = null
        monitoredNetwork = null
    }

    private fun notifyDefaultInterface(network: android.net.Network?) {
        val listener = defaultInterfaceListener ?: return

        runCatching {
            if (network == null) {
                android.util.Log.w(TAG, "default network monitor: no underlying network")
                listener.updateDefaultInterface("", -1, false, false)
                listener.updateNetworkPath("")
                return@runCatching
            }

            val connectivity = vpnService.getSystemService(ConnectivityManager::class.java)
            if (isVpnNetwork(connectivity, network)) {
                android.util.Log.w(TAG, "default network monitor: ignoring VPN network=$network")
                return@runCatching
            }

            val linkProperties = connectivity.getLinkProperties(network)
            val interfaceName = linkProperties?.interfaceName.orEmpty()
            val interfaceIndex = interfaceName.takeIf { it.isNotBlank() }
                ?.let { JNetworkInterface.getByName(it)?.index }
                ?: -1

            if (interfaceName.isBlank() || interfaceIndex < 0) {
                android.util.Log.w(
                    TAG,
                    "default network monitor: unusable network=$network iface='$interfaceName' index=$interfaceIndex"
                )
                return@runCatching
            }

            monitoredNetwork = network
            android.util.Log.i(
                TAG,
                "default network monitor: selected network=$network iface=$interfaceName index=$interfaceIndex"
            )
            listener.updateDefaultInterface(interfaceName, interfaceIndex, false, false)
            listener.updateNetworkPath(interfaceName)
        }.onFailure {
            android.util.Log.w(TAG, "default interface callback failed: " + it.message)
            runCatching {
                listener.updateDefaultInterface("", -1, false, false)
                listener.updateNetworkPath("")
            }
        }
    }

    private fun isVpnNetwork(
        connectivity: ConnectivityManager,
        network: android.net.Network
    ): Boolean {
        return connectivity.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

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
            // Exclude our own VPN network. Once the tun is up, OmniVPN's own
            // interface is a member of allNetworks like any other; surfacing it
            // here would let it be picked up as a candidate "physical" interface
            // by anything that walks this list, which is exactly the kind of
            // self-referential loop protect()/auto-detect-interface is meant to
            // avoid (see notifyDefaultInterface's identical isVpnNetwork guard).
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val nativeInterface =
                systemInterfaces.find { it.name == lp.interfaceName }
                    ?: return@mapNotNull null

            NetworkInterface().apply {
                name = lp.interfaceName
                dnsServer = StringArray(lp.dnsServers.mapNotNull { it.hostAddress })
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
                addresses = StringArray(nativeInterface.interfaceAddresses.map { it.toPrefix() })
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
            if (address.isNotBlank()) {
                android.util.Log.i(TAG, "openTun: addDnsServer=$address")
                builder.addDnsServer(address)
            }
        }
    }

    private fun addRoutes(builder: VpnService.Builder, options: TunOptions) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val r4 = options.inet4RouteAddress
            var hasV4Route = false
            while (r4.hasNext()) {
                hasV4Route = true
                val route = r4.next()
                android.util.Log.i(TAG, "openTun: addRoute4=${route.address()}/${route.prefix()}")
                builder.addRoute(route.address(), route.prefix())
            }
            if (!hasV4Route && options.inet4Address.hasNext()) {
                android.util.Log.i(TAG, "openTun: addRoute4=0.0.0.0/0 (fallback)")
                builder.addRoute("0.0.0.0", 0)
            }

            val r6 = options.inet6RouteAddress
            var hasV6Route = false
            while (r6.hasNext()) {
                hasV6Route = true
                val route = r6.next()
                android.util.Log.i(TAG, "openTun: addRoute6=${route.address()}/${route.prefix()}")
                builder.addRoute(route.address(), route.prefix())
            }
            if (!hasV6Route && options.inet6Address.hasNext()) {
                android.util.Log.i(TAG, "openTun: addRoute6=::/0 (fallback)")
                builder.addRoute("::", 0)
            }

            val x4 = options.inet4RouteExcludeAddress
            while (x4.hasNext()) {
                builder.excludeRoute(x4.next().toIpPrefix())
            }

            val x6 = options.inet6RouteExcludeAddress
            while (x6.hasNext()) {
                builder.excludeRoute(x6.next().toIpPrefix())
            }
        } else {
            val r4 = options.inet4RouteRange
            while (r4.hasNext()) {
                val route = r4.next()
                builder.addRoute(route.address(), route.prefix())
            }

            val r6 = options.inet6RouteRange
            while (r6.hasNext()) {
                val route = r6.next()
                builder.addRoute(route.address(), route.prefix())
            }

            // Route-exclude iterators are API 33+ inputs. Consume them on
            // older Android releases because no Builder.excludeRoute() exists.
            val x4 = options.inet4RouteExcludeAddress
            while (x4.hasNext()) x4.next()
            val x6 = options.inet6RouteExcludeAddress
            while (x6.hasNext()) x6.next()
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
        values: List<String>
    ) : StringIterator {
        private val values = values.toList()
        private val iterator = this.values.iterator()

        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = if (iterator.hasNext()) iterator.next() else ""
        override fun len(): Int = values.size
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

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun RoutePrefix.toIpPrefix(): IpPrefix =
    IpPrefix(InetAddress.getByName(address()), prefix())
