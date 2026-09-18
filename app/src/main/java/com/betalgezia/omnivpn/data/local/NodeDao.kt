package com.betalgezia.omnivpn.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes ORDER BY name")
    fun observeAll(): Flow<List<NodeEntity>>

    @Insert
    suspend fun insert(node: NodeEntity): Long

    @Delete
    suspend fun delete(node: NodeEntity)

    @Query("DELETE FROM nodes")
    suspend fun deleteAll()
}
