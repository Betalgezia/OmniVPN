package com.betalgezia.omnivpn.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Periodically refreshes every enabled subscription in the background (see the
 * Nocturne v2 handoff, "Автообновление подписок").
 *
 * A plain [CoroutineWorker] rather than a Hilt @HiltWorker: WorkManager's
 * default auto-initialisation (via androidx.startup) then keeps working
 * untouched, and the one dependency this needs is pulled from an
 * [AutoUpdateEntryPoint] instead of constructor injection - no
 * hilt-work/custom WorkerFactory to wire up.
 */
class SubscriptionAutoUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AutoUpdateEntryPoint {
        fun subscriptionRepository(): SubscriptionRepository
    }

    override suspend fun doWork(): Result {
        val repo = EntryPointAccessors
            .fromApplication(applicationContext, AutoUpdateEntryPoint::class.java)
            .subscriptionRepository()
        return runCatching {
            val subs = repo.subscriptions.first().filter { it.enabled }
            var anyFailed = false
            for (subscription in subs) {
                runCatching { repo.refresh(subscription) }
                    .onFailure {
                        anyFailed = true
                        android.util.Log.w(TAG, "auto-update: refresh failed for id=${subscription.id}", it)
                    }
            }
            // A transient network failure on one subscription shouldn't burn
            // the whole run: ask WorkManager to retry with its own backoff.
            if (anyFailed) Result.retry() else Result.success()
        }.getOrElse {
            android.util.Log.w(TAG, "auto-update: run failed", it)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SubAutoUpdate"
    }
}

/**
 * Schedules / cancels the periodic auto-update work. @Singleton so the
 * ViewModel can inject it and (re)schedule whenever the toggle or interval
 * changes; enqueue uses ExistingPeriodicWorkPolicy.UPDATE so re-scheduling on
 * every app start is idempotent.
 */
@Singleton
class SubscriptionAutoUpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun schedule(intervalMinutes: Int) {
        // WorkManager's own floor is 15 min, which also happens to be the
        // product's minimum - clamp so a stale/smaller value can't be rejected.
        val interval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES).toLong()
        val request = PeriodicWorkRequestBuilder<SubscriptionAutoUpdateWorker>(
            interval, TimeUnit.MINUTES
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        ).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    companion object {
        const val MIN_INTERVAL_MINUTES = 15
        private const val UNIQUE_NAME = "subscription-auto-update"
    }
}
