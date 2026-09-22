package com.betalgezia.omnivpn.vpn

/**
 * Cloudflare WARP anycast endpoints to try when the default one won't pass
 * traffic.
 *
 * WARP answers the same account on any of its anycast addresses, so these
 * differ only in which IP/port pair the handshake is aimed at - which is
 * exactly the variable that matters when a middlebox throttles or drops one
 * specific pair while leaving others alone. The port spread is deliberate:
 * 500/4500/1701 are the IPSec and L2TP ports, which tend to survive where an
 * unusual high port does not, and both /24 blocks Cloudflare publishes for
 * WARP are represented so a block on one range still leaves candidates.
 *
 * Ordered by how likely they are to work, cheapest first: [WarpAccount.DEFAULT_ENDPOINT]
 * leads, so an already-working setup is confirmed on the first probe.
 */
object WarpEndpoints {
    val CANDIDATES: List<String> = listOf(
        WarpAccount.DEFAULT_ENDPOINT,
        "162.159.192.1:500",
        "162.159.192.1:1701",
        "162.159.192.1:4500",
        "162.159.193.10:2408",
        "162.159.195.1:2408",
        "188.114.96.1:2408",
        "188.114.97.1:500",
        "188.114.98.1:1701",
        "188.114.99.1:4500",
        "162.159.192.1:894",
        "188.114.96.1:987"
    ).distinct()
}
