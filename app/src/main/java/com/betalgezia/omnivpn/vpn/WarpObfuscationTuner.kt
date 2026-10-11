package com.betalgezia.omnivpn.vpn

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** One line in the tuner's rolling log: a candidate and its probe result. */
data class TuneLog(val candidate: AwgObfuscation, val latencyMs: Int?)

/** UI-facing state of an obfuscation-tuning run. */
sealed interface TuneState {
    data object Idle : TuneState
    data class Running(val step: Int, val total: Int, val log: List<TuneLog>, val okCount: Int) : TuneState
    data class Found(val best: AwgObfuscation, val latencyMs: Int, val okCount: Int, val total: Int) : TuneState
    data class NotFound(val total: Int) : TuneState
}

/**
 * Manual obfuscation autotuner for WARP. Walks a fixed, ordered list of
 * junk/decoy shapes across a few Cloudflare anycast endpoints, probing each
 * as a real (TUN-less) AmneziaWG-over-WARP session through [NodeHealthChecker]
 * - the same probe the "find working endpoint" scanner uses - and picks the
 * fastest shape that actually passes traffic.
 *
 * Design choices (see the September 2026 handoff `awg_autotune.md`, adapted):
 *  - Only Jc/Jmin/Jmax and the i1 decoy are varied; s1-s4/h1-h4 stay pinned
 *    (see [AwgObfuscation]) so every candidate is still a valid vanilla-WARP
 *    client. A malformed decoy just makes that one candidate fail to build
 *    and is reported as a miss, never a crash.
 *  - Candidate #1 is the current built-in default on the saved endpoint, so
 *    an already-working setup is confirmed on the first probe.
 *  - Candidates are probed strictly one at a time. Every candidate reuses the
 *    same WARP account/keys, so overlapping sessions to Cloudflare with
 *    identical keys could interfere - and there is only one sing-box engine
 *    process-wide anyway (see [SingBoxEngine]).
 *  - Tuning requires the VPN to be disconnected, for that same single-engine
 *    reason - the caller gates this (canTestNow). The handoff imagined probing
 *    while connected; this architecture cannot, so that was dropped.
 *
 * Each candidate is checked with [NodeHealthChecker.checkSustained]: one probe
 * tunnel is held open ~18s and traffic is tested twice, so a shape that passes
 * the handshake but goes silent ~15s in (WARP's characteristic DPI death - see
 * the notes on WarpAccount.DEFAULT_OBFUSCATION) is rejected rather than chosen.
 * Dead shapes still fail fast on the first probe and never pay the hold, so a
 * run's length scales with how many candidates actually survive the handshake -
 * on a network where WARP is heavily blocked (the case this feature is for),
 * few do, so few holds are paid.
 */
@Singleton
class WarpObfuscationTuner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val healthChecker: NodeHealthChecker,
    private val store: WarpObfuscationStore
) {
    fun tune(account: WarpAccount, includeIpv6: Boolean): Flow<TuneState> = flow {
        val candidates = buildCandidates(account).take(MAX_CANDIDATES)
        val successes = mutableListOf<Pair<AwgObfuscation, Int>>()
        val log = ArrayDeque<TuneLog>()

        candidates.forEachIndexed { index, obf ->
            val probeAccount = account.copy(endpoint = obf.endpoint ?: account.endpoint)
            val node = probeAccount.toNode(includeIpv6 = includeIpv6, obfuscation = obf)
                .copy(id = index + 1L, name = "tune-${index + 1}")
            // Sustained probe: pass only if traffic still flows after the hold,
            // so a shape that dies ~15s in is a miss, not the winner.
            val health = healthChecker.checkSustained(
                node,
                firstTimeoutMs = CANDIDATE_TIMEOUT_MS,
                secondTimeoutMs = CANDIDATE_TIMEOUT_MS
            )
            val ms = (health as? NodeHealth.Reachable)?.latencyMs?.toInt()
            if (ms != null) successes += obf.copy(tunedLatencyMs = ms) to ms
            log.addFirst(TuneLog(obf, ms))
            while (log.size > LOG_LINES) log.removeLast()
            emit(TuneState.Running(index + 1, candidates.size, log.toList(), successes.size))
        }

        val best = successes.minByOrNull { it.second }
        emit(
            if (best != null) TuneState.Found(best.first, best.second, successes.size, candidates.size)
            else TuneState.NotFound(candidates.size)
        )
    }.flowOn(Dispatchers.Default)

    fun apply(obf: AwgObfuscation) = store.save(store.currentNetworkKey(), obf)
    fun reset() = store.clear()

    private fun buildCandidates(account: WarpAccount): List<AwgObfuscation> {
        val endpoints = topEndpoints(account)
        return endpoints.flatMap { ep -> SHAPES.map { it.copy(endpoint = ep) } }
    }

    private fun topEndpoints(account: WarpAccount): List<String> {
        val primary = account.endpoint
        val extras = WarpEndpoints.CANDIDATES.filter { it != primary }
        return (listOf(primary) + extras).distinct().take(MAX_ENDPOINTS)
    }

    private companion object {
        const val MAX_CANDIDATES = 15
        const val MAX_ENDPOINTS = 3
        const val CANDIDATE_TIMEOUT_MS = 5000
        const val LOG_LINES = 4

        // A QUIC-initial-shaped decoy whose connection-id/payload is fresh
        // random each handshake (`<r>` is generated by sing-box): unlike the
        // proven static blob, this isn't byte-identical to the one many
        // AmneziaWG+WARP users copy from the same public guides, so it's less
        // exposed to being fingerprinted by that exact blob. 0xc0 = QUIC long
        // header (initial), 0x00000001 = QUIC v1, 0x08 = 8-byte DCID.
        const val FRESH_QUIC_I1 = "<b 0xc00000000108><r 700>"

        // Fixed, ordered shapes - NOT a combinatorial sweep. #1 is the current
        // built-in default (see WarpAccount.DEFAULT_OBFUSCATION); jmax stays
        // <= 1280 (MTU) and jc <= 10 so the junk burst never delays the real
        // handshake much. The i1 for the first two reuses the proven blob.
        val SHAPES: List<AwgObfuscation> = listOf(
            AwgObfuscation(jc = 3, jmin = 64, jmax = 128, i1 = WarpAccount.DEFAULT_OBFUSCATION.i1),
            AwgObfuscation(jc = 4, jmin = 40, jmax = 70, i1 = WarpAccount.DEFAULT_OBFUSCATION.i1),
            AwgObfuscation(jc = 4, jmin = 100, jmax = 200, i1 = FRESH_QUIC_I1),
            AwgObfuscation(jc = 5, jmin = 100, jmax = 200, i1 = FRESH_QUIC_I1),
            AwgObfuscation(jc = 3, jmin = 16, jmax = 48, i1 = FRESH_QUIC_I1),
            AwgObfuscation(jc = 8, jmin = 64, jmax = 128, i1 = null)
        )
    }
}
