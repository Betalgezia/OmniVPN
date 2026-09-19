package com.betalgezia.omnivpn.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes ORDER BY name")
    fun observeAll(): Flow<List<NodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: NodeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(nodes: List<NodeEntity>): List<Long>

    @Delete
    suspend fun delete(node: NodeEntity)

    @Query("DELETE FROM nodes WHERE sourceId = :sourceId")
    suspend fun deleteBySourceId(sourceId: Long)

    @Query("DELETE FROM nodes WHERE sourceId IS NULL")
    suspend fun deleteManual()

    @Transaction
    suspend fun replaceManual(nodes: List<NodeEntity>) {
        deleteManual()
        if (nodes.isNotEmpty()) insertAll(nodes)
    }

    @Transaction
    suspend fun replaceBySourceId(sourceId: Long, nodes: List<NodeEntity>) {
        deleteBySourceId(sourceId)
        if (nodes.isNotEmpty()) insertAll(nodes)
    }

    @Query("DELETE FROM nodes WHERE name = :name AND server = :server AND port = :port")
    suspend fun deleteKnownDemoNode(
        name: String,
        server: String,
        port: Int
    )

    @Query("DELETE FROM nodes")
    suspend fun deleteAll()
}
