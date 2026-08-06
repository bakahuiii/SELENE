package io.github.bakahuiii.selene.context

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

/**
 * Periodic, local-only collection. Each collector is permission-aware so a
 * missing optional grant never prevents the lower-sensitivity snapshots.
 */
class AutoContextWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    private data class AppSession(val packageName: String, val startMillis: Long, val endMillis: Long) {
        val durationMillis get() = (endMillis - startMillis).coerceAtLeast(0)
    }

    override fun doWork(): Result {
        val context = applicationContext
        if (!AutoCollectionSettings.isEnabled(context) || ContextOutput.outputTreeUri(context) == null) return Result.success()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val previous = AutoCollectionSettings.lastSuccessfulCaptureAt(context)
        val windowStart = previous.takeIf { it in dayStart until now } ?: (now - 60 * 60_000L)
        val events = mutableListOf<JSONObject>()
        val appSessions = foregroundSessions(windowStart, now)

        screenUsageEvent(windowStart, now, appSessions)?.let(events::add)
        events += detailedAppActivityEvents(appSessions)
        events += calendarEventsForToday(dayStart, zone)
        deviceSnapshot(windowStart, now)?.let(events::add)
        networkSnapshot(windowStart, now)?.let(events::add)
        backgroundLocationObservation(now)?.let(events::add)

        return try {
            if (events.isNotEmpty()) ContextOutput.writeEvents(context, events)
            AutoCollectionSettings.markSuccessfulCapture(context, now)
            Result.success()
        } catch (_: SecurityException) {
            // A document-tree permission may have been revoked. Do not loop forever.
            Result.failure()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun screenUsageEvent(start: Long, end: Long, sessions: List<AppSession>): JSONObject? {
        if (!hasUsageAccess()) return null
        // queryUsageStats may report cumulative counters outside this worker's
        // window. Use the same bounded foreground transitions as activity data.
        val foregroundMillis = sessions.sumOf(AppSession::durationMillis).coerceAtMost((end - start).coerceAtLeast(0))
        val appCount = sessions.map(AppSession::packageName).distinct().size
        return event(
            id = "SELENE-auto-screen-$start",
            kind = "screen-time",
            startAt = iso(start),
            endAt = iso(end),
            title = "Screen usage snapshot",
            values = JSONObject()
                .put("foregroundSeconds", (foregroundMillis / 1_000).toInt())
                .put("activeAppCount", appCount)
                .put("windowSeconds", ((end - start) / 1_000).toInt()),
        )
    }

    private fun detailedAppActivityEvents(sessions: List<AppSession>): List<JSONObject> {
        return sessions
            .filter { it.durationMillis >= 10_000L }
            .map { session ->
                val appName = applicationLabel(session.packageName)
                val packageToken = Integer.toUnsignedString(session.packageName.hashCode(), 16)
                event(
                    id = "SELENE-auto-activity-$packageToken-${session.startMillis}",
                    kind = "activity",
                    startAt = iso(session.startMillis),
                    endAt = iso(session.endMillis),
                    title = "App activity: $appName",
                    values = JSONObject()
                        .put("application", appName)
                        .put("durationSeconds", (session.durationMillis / 1_000).toInt())
                        .put("detail", "foreground-session"),
                )
            }
    }

    private fun foregroundSessions(start: Long, end: Long): List<AppSession> {
        if (!hasUsageAccess()) return emptyList()
        val manager = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val usageEvents = manager.queryEvents((start - 6 * 60 * 60_000L).coerceAtLeast(0), end)
        val rawSessions = mutableListOf<AppSession>()
        var activePackage: String? = null
        var activeStart = start

        fun closeActive(endMillis: Long) {
            val packageName = activePackage ?: return
            val boundedStart = activeStart.coerceAtLeast(start)
            val boundedEnd = endMillis.coerceAtMost(end)
            if (boundedEnd > boundedStart) rawSessions += AppSession(packageName, boundedStart, boundedEnd)
            activePackage = null
        }

        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            val packageName = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (packageName == activePackage) continue
                    closeActive(event.timeStamp)
                    if (!isSystemNoise(packageName)) {
                        activePackage = packageName
                        activeStart = event.timeStamp
                    }
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (packageName == activePackage) closeActive(event.timeStamp)
            }
        }
        closeActive(end)

        return mergeNearbySessions(rawSessions)
    }

    private fun calendarEventsForToday(dayStart: Long, zone: ZoneId): List<JSONObject> {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) return emptyList()
        val dayEnd = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(dayStart.toString()).appendPath(dayEnd.toString()).build()
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.TITLE,
        )
        return runCatching {
            applicationContext.contentResolver.query(uri, columns, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val eventId = cursor.getLong(0)
                        val begin = cursor.getLong(1)
                        val finish = cursor.getLong(2)
                        val title = cursor.getString(3)?.trim().takeUnless { it.isNullOrBlank() } ?: "Calendar event"
                        add(event(
                            id = "SELENE-calendar-$eventId-$begin",
                            kind = "calendar",
                            startAt = iso(begin),
                            endAt = iso(finish),
                            title = title,
                            values = JSONObject().put("durationMinutes", ((finish - begin).coerceAtLeast(0) / 60_000).toInt()),
                        ))
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun deviceSnapshot(start: Long, end: Long): JSONObject? = runCatching {
        val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val power = applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        event(
            id = "SELENE-auto-device-$start",
            kind = "device",
            startAt = iso(start),
            endAt = iso(end),
            title = "Device state snapshot",
            values = JSONObject()
                .put("batteryPercent", if (level >= 0 && scale > 0) (level * 100 / scale) else -1)
                .put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
                .put("plugged", plugged != 0)
                .put("screenInteractive", power.isInteractive)
                .put("powerSaveMode", power.isPowerSaveMode)
                .put("uptimeMinutes", (SystemClock.elapsedRealtime() / 60_000).toInt()),
        )
    }.getOrNull()

    private fun networkSnapshot(start: Long, end: Long): JSONObject? = runCatching {
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val transports = buildList {
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
        }
        val values = JSONObject()
            .put("connected", capabilities != null)
            .put("transport", transports.joinToString(",").ifBlank { "none" })
            .put("metered", connectivity.isActiveNetworkMetered)
        wifiFingerprint()?.let { values.put("wifiFingerprint", it) }
        event(
            id = "SELENE-auto-network-$start",
            kind = "device",
            startAt = iso(start),
            endAt = iso(end),
            title = "Network state snapshot",
            values = values,
        )
    }.getOrNull()

    private fun backgroundLocationObservation(now: Long): JSONObject? {
        if (!AutoCollectionSettings.backgroundLocationEnabled(applicationContext) || !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) return null
        val manager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val location = runCatching {
            manager.getProviders(true)
                .mapNotNull { provider -> lastKnownLocation(manager, provider) }
                .maxByOrNull(Location::getTime)
        }.getOrNull() ?: return null
        val ageMillis = (now - location.time).coerceAtLeast(0)
        // The foreground movement service owns continuous tracking. This hourly
        // worker is only a fresh-place fallback when that service was stopped or
        // temporarily unable to obtain a fix; never turn an old cached point
        // into a movement observation.
        if (ageMillis > 30 * 60_000L) return null
        val consentAt = AutoCollectionSettings.backgroundLocationConsentAt(applicationContext)
        val observation = PlaceTagger.observe(applicationContext, location)
        val placeTag = if (
            AutoCollectionSettings.onlinePlaceEnrichmentEnabled(applicationContext) &&
            observation.needsEnrichment &&
            AutoCollectionSettings.canRequestOnlinePlace(applicationContext)
        ) {
            AutoCollectionSettings.markOnlinePlaceRequest(applicationContext)
            PlaceTagger.markEnrichmentAttempt(applicationContext, observation.id)
            OnlinePlaceEnricher.resolve(observation)?.also { label ->
                PlaceTagger.saveEnrichedLabel(applicationContext, observation.id, label)
            } ?: observation.tag
        } else {
            observation.tag
        }
        return event(
            id = "SELENE-auto-location-${location.time}",
            kind = "location",
            startAt = iso(location.time),
            endAt = null,
            title = "Latest location fallback",
            values = JSONObject()
                .put("sampleMode", "last-known-fallback")
                .put("movementTracking", "foreground-service")
                .put("ageSeconds", (ageMillis / 1_000).toInt())
                .put("placeTag", placeTag),
        ).apply {
            put("privacy", "precise")
            put("location", JSONObject()
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("accuracyMeters", location.accuracy)
            )
            put("locationConsent", JSONObject()
                .put("exactLocation", true)
                .put("captureMode", "background")
                .put("grantedAt", iso(consentAt))
            )
        }
    }

    private fun lastKnownLocation(manager: LocationManager, provider: String): Location? {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        return try {
            manager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            null
        }
    }

    private fun mergeNearbySessions(sessions: List<AppSession>): List<AppSession> {
        val merged = mutableListOf<AppSession>()
        for (session in sessions.sortedWith(compareBy(AppSession::startMillis, AppSession::packageName))) {
            val previous = merged.lastOrNull()
            if (previous != null && previous.packageName == session.packageName && session.startMillis - previous.endMillis <= 60_000L) {
                merged[merged.lastIndex] = previous.copy(endMillis = maxOf(previous.endMillis, session.endMillis))
            } else {
                merged += session
            }
        }
        return merged
    }

    private fun applicationLabel(packageName: String): String = runCatching {
        applicationContext.packageManager.getApplicationInfo(packageName, 0)
            .loadLabel(applicationContext.packageManager)
            .toString()
            .trim()
    }.getOrNull().takeUnless { it.isNullOrBlank() } ?: packageName

    private fun wifiFingerprint(): String? {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ssid = runCatching { wifi.connectionInfo?.ssid?.trim() }.getOrNull()
            ?.takeUnless { it.isNullOrBlank() || it == "<unknown ssid>" }
            ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(ssid.toByteArray(Charsets.UTF_8))
        return "sha256-" + digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun isSystemNoise(packageName: String) = packageName == applicationContext.packageName || packageName == "android" || packageName == "com.android.systemui"

    private fun hasPermission(permission: String) = applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(): Boolean {
        val appOps = applicationContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            applicationContext.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    private fun event(id: String, kind: String, startAt: String, endAt: String?, title: String, values: JSONObject): JSONObject {
        val capturedAt = iso(System.currentTimeMillis())
        return JSONObject()
            .put("id", id)
            .put("version", 1)
            .put("kind", kind)
            .put("source", "selene")
            .put("startAt", startAt)
            .apply { if (endAt != null) put("endAt", endAt) }
            .put("title", title)
            .put("values", values)
            .put("capturedAt", capturedAt)
            .put("privacy", "coarse")
    }

    private fun iso(millis: Long) = ContextOutput.iso(millis)
}
