package com.betalgezia.omnivpn.vpn

import android.content.Context
import com.betalgezia.omnivpn.data.model.Node
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finds a Cloudflare WARP anycast endpoint that actually passes traffic, and
 * saves it as the account's endpoint.
 *
 * WARP's failure mode under DPI is not a refused connection - the handshake
 * completes and then the tunnel goes quiet (see the notes on
 * [WarpAccount.DPI_JUNK_OBFUSCATION]), so "is this endpoint usable" can only be
 * answered by pushing real traffic through it. That is exactly what
 * [NodeHealthChecker] already does, so this builds one throwaway AmneziaWG node
 * per candidate - same account and keys, only the peer endpoint differs - and
 * takes the first that comes back reachable.
 */
@Singleton
class WarpEndpointScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthChecker: NodeHealthChecker
) {

    data class Result(val endpoint: String, val latencyMs: Long, val attempts: Int)

    suspend fun findWorkingEndpoint(
        candidates: List<String> = WarpEndpoints.CANDIDATES
    ): kotlin.Result<Result> = runCatching {
        val storage = WarpStorage(context)
        val account = storage.get()
            ?: error("Register WARP first, then search for an endpoint")

        // Synthetic ids only: these nodes are never persisted, but
        // SingBoxConfigBuilder.buildProbeConfig tags outbounds by node id, so
        // they still have to be unique and non-zero. The name carries the
        // candidate so the result can be read straight back off the node.
        val probes = candidates.mapIndexed { index, endpoint ->
            account.copy(endpoint = endpoint).toNode().copy(id = index + 1L, name = endpoint)
        }

        var winner: Result? = null
        var attempts = 0
        healthChecker.checkAll(probes, stopOnFirstReachable = true) { node: Node, health: NodeHealth ->
            attempts++
            android.util.Log.i(TAG, "endpoint ${node.name}: $health")
            if (winner == null && health is NodeHealth.Reachable) {
                winner = Result(node.name, health.latencyMs, attempts)
            }
        }

        val found = winner
            ?: error("None of the ${candidates.size} WARP endpoints passed traffic")
        storage.set(account.copy(endpoint = found.endpoint))
        android.util.Log.i(TAG, "saved working WARP endpoint ${found.endpoint} (${found.latencyMs} ms)")
        found
    }

    private companion object {
        const val TAG = "WarpEndpointScanner"
    }
}
