package com.betalgezia.omnivpn.data

import com.betalgezia.omnivpn.data.local.NodeDao
import com.betalgezia.omnivpn.data.local.toDomain
import com.betalgezia.omnivpn.data.local.toEntity
import com.betalgezia.omnivpn.data.model.Node
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NodeRepository @Inject constructor(private val dao: NodeDao) {
    val nodes: Flow<List<Node>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun add(node: Node): Long = dao.insert(node.toEntity())
    suspend fun addAll(nodes: List<Node>): List<Long> = dao.insertAll(nodes.map(Node::toEntity))
    suspend fun replaceAll(nodes: List<Node>) {
        dao.deleteAll()
        if (nodes.isNotEmpty()) dao.insertAll(nodes.map(Node::toEntity))
    }
    suspend fun delete(node: Node) {
        if (node.id == 0L) return
        dao.delete(node.toEntity())
    }
}