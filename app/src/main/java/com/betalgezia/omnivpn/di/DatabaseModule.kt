package com.betalgezia.omnivpn.di

import android.content.Context
import androidx.room.Room
import com.betalgezia.omnivpn.data.local.OmniVpnDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): OmniVpnDatabase =
        Room.databaseBuilder(
            context,
            OmniVpnDatabase::class.java,
            "omnivpn.db"
        )
            .addMigrations(com.betalgezia.omnivpn.data.local.OmniVpnMigrations.MIGRATION_1_2)
            .build()

    @Provides
    fun provideNodeDao(database: OmniVpnDatabase) = database.nodeDao()

    @Provides
    fun provideSubscriptionDao(database: OmniVpnDatabase) = database.subscriptionDao()
}
