package com.betalgezia.omnivpn.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): SubscriptionEntity?

    @Insert
    suspend fun insert(subscription: SubscriptionEntity): Long

    @Delete
    suspend fun delete(subscription: SubscriptionEntity)

    @Query("UPDATE subscriptions SET lastUpdatedAt = :updatedAt WHERE id = :id")
    suspend fun markUpdated(id: Long, updatedAt: Long)
}
