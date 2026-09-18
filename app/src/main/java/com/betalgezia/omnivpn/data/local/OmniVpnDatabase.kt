package com.betalgezia.omnivpn.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [NodeEntity::class, SubscriptionEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class OmniVpnDatabase : RoomDatabase() {
    abstract fun nodeDao(): NodeDao
    abstract fun subscriptionDao(): SubscriptionDao
}
