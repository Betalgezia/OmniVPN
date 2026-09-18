package com.betalgezia.omnivpn.data

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.vpn.ConfigParser
import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(val nodes: List<Node>, val unsupportedEntries: Int = 0)

private fun identityKey(node: Node): String = listOf(
    node.protocol.name, node.server, node.port, node.uuid.orEmpty(),
    node.password.orEmpty(), node.privateKey.orEmpty(), node.rawConfig.orEmpty()
).joinToString("\u001f")

@Singleton
class NodeImportService @Inject constructor(private val repository: NodeRepository) {
    suspend fun importText(text: String): ImportResult {
        val parsed = ConfigParser.parse(text)
        require(parsed.isNotEmpty()) { "Configuration contains no supported nodes" }
        repository.addAll(parsed.distinctBy(::identityKey))
        return ImportResult(parsed)
    }

    suspend fun replaceWithText(text: String): ImportResult {
        val parsed = ConfigParser.parse(text)
        require(parsed.isNotEmpty()) { "Configuration contains no supported nodes" }
        repository.replaceAll(parsed.distinctBy(::identityKey))
        return ImportResult(parsed)
    }
}