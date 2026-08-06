package io.github.bakahuiii.selene.context

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** One-time configuration for the automatic, local-only collection flow. */
object AutoCollectionSettings {
    private const val preferencesName = "SELENE"
    private const val enabledKey = "automatic-collection-enabled"
    private const val backgroundLocationKey = "automatic-background-location-enabled"
    private const val backgroundLocationConsentAtKey = "automatic-background-location-consent-at"
    private const val onlinePlaceEnrichmentKey = "online-place-enrichment-enabled"
    private const val lastOnlinePlaceRequestAtKey = "online-place-enrichment-last-request-at"
    private const val lastSuccessfulCaptureKey = "automatic-last-successful-capture"
    private const val minimumOnlinePlaceRequestIntervalMillis = 1_500L

    fun isEnabled(context: Context) = preferences(context).getBoolean(enabledKey, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(enabledKey, enabled).apply()
    }

    fun backgroundLocationEnabled(context: Context) = preferences(context).getBoolean(backgroundLocationKey, false)

    fun setBackgroundLocationEnabled(context: Context, enabled: Boolean) {
        val editor = preferences(context).edit().putBoolean(backgroundLocationKey, enabled)
        if (enabled) editor.putLong(backgroundLocationConsentAtKey, System.currentTimeMillis())
        editor.apply()
    }

    fun onlinePlaceEnrichmentEnabled(context: Context) = preferences(context)
        .getBoolean(onlinePlaceEnrichmentKey, false)

    fun setOnlinePlaceEnrichmentEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(onlinePlaceEnrichmentKey, enabled).apply()
    }

    fun canRequestOnlinePlace(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val previous = preferences(context).getLong(lastOnlinePlaceRequestAtKey, 0L)
        return now - previous >= minimumOnlinePlaceRequestIntervalMillis
    }

    fun markOnlinePlaceRequest(context: Context, requestedAt: Long = System.currentTimeMillis()) {
        preferences(context).edit().putLong(lastOnlinePlaceRequestAtKey, requestedAt).apply()
    }

    fun backgroundLocationConsentAt(context: Context) = preferences(context)
        .getLong(backgroundLocationConsentAtKey, 0L)
        .takeIf { it > 0L }
        ?: System.currentTimeMillis()

    fun lastSuccessfulCaptureAt(context: Context) = preferences(context).getLong(lastSuccessfulCaptureKey, 0L)

    fun markSuccessfulCapture(context: Context, capturedAt: Long) {
        preferences(context).edit().putLong(lastSuccessfulCaptureKey, capturedAt).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
}

object AutoCollectionScheduler {
    private const val periodicWorkName = "SELENE-automatic-context-hourly"
    private const val initialWorkName = "SELENE-automatic-context-initial"

    fun start(context: Context) {
        val constraints = Constraints.Builder().setRequiresStorageNotLow(true).build()
        val periodic = PeriodicWorkRequestBuilder<AutoContextWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            periodicWorkName,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        val initial = OneTimeWorkRequestBuilder<AutoContextWorker>()
            .setInitialDelay(15, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(initialWorkName, ExistingWorkPolicy.REPLACE, initial)
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(periodicWorkName)
        WorkManager.getInstance(context).cancelUniqueWork(initialWorkName)
    }
}
