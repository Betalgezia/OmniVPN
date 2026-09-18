package com.betalgezia.omnivpn.data

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.vpn.ConfigParser
import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(val nodes: List<Node>, val unsupportedEntries: Int = 0)

@Singleton
class NodeImportService @Inject constructor(private val repository: NodeRepository) {
    suspend fun importText(text: String): ImportResult {
        val parsed = ConfigParser.parse(text)
        repository.addAll(parsed)
        return ImportResult(parsed)
    }

    suspend fun replaceWithText(text: String): ImportResult {
        val parsed = ConfigParser.parse(text)
        repository.replaceAll(parsed)
        return ImportResult(parsed)
    }
}