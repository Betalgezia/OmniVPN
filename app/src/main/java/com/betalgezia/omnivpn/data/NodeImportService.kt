package com.betalgezia.omnivpn.data

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.vpn.ConfigParser
import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(
    val nodes: List<Node>,
    val unsupportedEntries: Int = 0,
    val domainWireguardEndpoint: Boolean = false
)

private fun identityKey(node: Node): String = listOf(
    node.protocol.name, node.server, node.port, node.uuid.orEmpty(),
    node.password.orEmpty(), node.privateKey.orEmpty(), node.rawConfig.orEmpty()
).joinToString("\u001f")

/**
 * True if any AmneziaWG/WireGuard node in [nodes] has a domain-name (not a
 * literal IP) peer endpoint. Such peers hit a confirmed sing-box-lx bug
 * where the handshake never completes on some networks - see the comment
 * on [ConfigParser.isLiteralIpHost] and on WarpAccount.DEFAULT_ENDPOINT.
 * Shared between plain-text import and subscription refresh so both paths
 * warn the same way.
 */
fun hasDomainWireguardEndpoint(nodes: List<Node>): Boolean =
    nodes.any { it.protocol == Protocol.AMNEZIAWG && !ConfigParser.isLiteralIpHost(it.server) }

@Singleton
class NodeImportService @Inject constructor(private val repository: NodeRepository) {
    suspend fun importText(text: String): ImportResult {
        val parsed = ConfigParser.parse(text)
        require(parsed.isNotEmpty()) { "Configuration contains no supported nodes" }
        val unique = parsed.distinctBy(::identityKey)
        repository.addAll(unique)
        return ImportResult(unique, unsupportedEntries = parsed.size - unique.size, domainWireguardEndpoint = hasDomainWireguardEndpoint(unique))
    }

    suspend fun replaceWithText(text: String): ImportResult {
        val parsed = ConfigParser.parse(text)
        require(parsed.isNotEmpty()) { "Configuration contains no supported nodes" }
        val unique = parsed.distinctBy(::identityKey)
        repository.replaceAll(unique)
        return ImportResult(unique, unsupportedEntries = parsed.size - unique.size, domainWireguardEndpoint = hasDomainWireguardEndpoint(unique))
    }
}