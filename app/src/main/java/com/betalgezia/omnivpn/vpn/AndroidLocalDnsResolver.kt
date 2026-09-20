package com.betalgezia.omnivpn.vpn

import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.CancellationSignal
import android.system.ErrnoException
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Android system DNS transport for sing-box local DNS.
 *
 * Android 10+ queries are bound to a non-VPN underlying Network so system
 * DNS cannot recurse back through the VPN TUN.
 */
class AndroidLocalDnsResolver(
    private val connectivity: ConnectivityManager
) : LocalDNSTransport {

    constructor(vpnService: VpnService) :
        this(vpnService.getSystemService(ConnectivityManager::class.java))

    override fun raw(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val network = findUnderlyingNetwork()
        if (network == null) {
            android.util.Log.w(TAG, "exchange: no underlying network")
            ctx.errorCode(RCODE_SERVFAIL)
            return
        }
        runBlocking {
            suspendCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                val signal = CancellationSignal()
                fun complete() {
                    if (finished.compareAndSet(false, true)) continuation.resume(Unit)
                }
                ctx.onCancel {
                    signal.cancel()
                    complete()
                }
                val callback = object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        if (rcode == 0) ctx.rawSuccess(answer) else ctx.errorCode(rcode)
                        complete()
                    }
                    override fun onError(error: DnsResolver.DnsException) {
                        when (val cause = error.cause) {
                            is ErrnoException -> ctx.errnoCode(cause.errno)
                            else -> {
                                android.util.Log.w(TAG, "exchange failed: ${error.message}")
                                ctx.errorCode(RCODE_SERVFAIL)
                            }
                        }
                        complete()
                    }
                }
                DnsResolver.getInstance().rawQuery(
                    network, message, DnsResolver.FLAG_NO_RETRY,
                    Dispatchers.IO.asExecutor(), signal, callback
                )
            }
        }
    }

    override fun lookup(ctx: ExchangeContext, networkName: String, domain: String) {
        val network = findUnderlyingNetwork()
        if (network == null) {
            android.util.Log.w(TAG, "lookup: no underlying network for $domain")
            ctx.errorCode(RCODE_SERVFAIL)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runBlocking {
                suspendCoroutine { continuation ->
                    val finished = AtomicBoolean(false)
                    val signal = CancellationSignal()
                    fun complete() {
                        if (finished.compareAndSet(false, true)) continuation.resume(Unit)
                    }
                    ctx.onCancel {
                        signal.cancel()
                        complete()
                    }
                    val callback = object : DnsResolver.Callback<Collection<InetAddress>> {
                        override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                            if (rcode == 0) {
                                val values = answer.mapNotNull { it.hostAddress }.joinToString("\n")
                                if (values.isNotBlank()) ctx.success(values) else ctx.errorCode(RCODE_NXDOMAIN)
                            } else {
                                ctx.errorCode(rcode)
                            }
                            complete()
                        }
                        override fun onError(error: DnsResolver.DnsException) {
                            when (val cause = error.cause) {
                                is ErrnoException -> ctx.errnoCode(cause.errno)
                                else -> {
                                    android.util.Log.w(TAG, "lookup failed for $domain: ${error.message}")
                                    ctx.errorCode(RCODE_SERVFAIL)
                                }
                            }
                            complete()
                        }
                    }
                    val type = when {
                        networkName.endsWith("4") -> DnsResolver.TYPE_A
                        networkName.endsWith("6") -> DnsResolver.TYPE_AAAA
                        else -> null
                    }
                    if (type != null) {
                        DnsResolver.getInstance().query(
                            network, domain, type, DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(), signal, callback
                        )
                    } else {
                        DnsResolver.getInstance().query(
                            network, domain, DnsResolver.FLAG_NO_RETRY,
                            Dispatchers.IO.asExecutor(), signal, callback
                        )
                    }
                }
            }
        } else {
            val answer = try {
                network.getAllByName(domain)
            } catch (_: UnknownHostException) {
                ctx.errorCode(RCODE_NXDOMAIN)
                return
            }
            val values = answer.mapNotNull { it.hostAddress }.joinToString("\n")
            if (values.isNotBlank()) ctx.success(values) else ctx.errorCode(RCODE_NXDOMAIN)
        }
    }

    private fun findUnderlyingNetwork(): Network? = runCatching {
        val candidates = connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@mapNotNull null
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            network to caps
        }
        candidates.firstOrNull { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }?.first
            ?: candidates.firstOrNull()?.first
    }.getOrNull()

    private companion object {
        private const val TAG = "AndroidLocalDns"
        private const val RCODE_NXDOMAIN = 3
        private const val RCODE_SERVFAIL = 2
    }
}
