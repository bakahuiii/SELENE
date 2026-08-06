package io.github.bakahuiii.selene.context

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Low-rate foreground location collector used while automatic background
 * location is enabled. WorkManager is intentionally not used for movement:
 * periodic work cannot observe a walk that starts and ends between two runs.
 */
class MovementTrackingService : Service() {
    private enum class MotionState { IDLE, CANDIDATE, MOVING }

    private data class TrackPoint(
        val location: Location,
        val timestamp: Long,
        val speedMps: Float,
        val distanceMeters: Float,
    )

    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pendingEvents = mutableListOf<JSONObject>()
    private val eventLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val candidatePoints = ArrayDeque<TrackPoint>()
    private var locationManager: LocationManager? = null
    private var lastAccepted: TrackPoint? = null
    private var state = MotionState.IDLE
    private var candidateEvidence = 0
    private var candidateStartedAt = 0L
    private var lastLocationAt = 0L
    private var lastMovementEvidenceAt = 0L
    private var stationarySince = 0L
    private var trackId: String? = null
    private var trackStartedAt = 0L
    private var trackLastAt = 0L
    private var trackDistanceMeters = 0.0
    private var trackMaxSpeedMps = 0f
    private var trackSampleCount = 0
    private var trackSequence = 0
    private var flushedAt = 0L
    private var running = true

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) = handleLocation(location)
    }
    private val inactivityWatchdog = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            when (state) {
                MotionState.CANDIDATE -> if (candidateStartedAt > 0L && now - candidateStartedAt >= CANDIDATE_TIMEOUT_MILLIS) resetToIdle()
                MotionState.MOVING -> if (lastLocationAt > 0L && now - lastLocationAt >= NO_EVIDENCE_END_MILLIS) finishMovement(lastLocationAt)
                MotionState.IDLE -> Unit
            }
            if (running) mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, notification("等待移动"))
        } catch (_: SecurityException) {
            stopSelf()
            return
        }
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        requestLocationUpdates()
        mainHandler.postDelayed(inactivityWatchdog, WATCHDOG_INTERVAL_MILLIS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!shouldRun()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (locationManager == null) requestLocationUpdates()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        mainHandler.removeCallbacks(inactivityWatchdog)
        locationManager?.let { manager ->
            runCatching { manager.removeUpdates(locationListener) }
        }
        finishMovement(lastLocationAt.takeIf { it > 0L } ?: System.currentTimeMillis())
        flushEvents(force = true)
        ioExecutor.shutdown()
        runCatching { ioExecutor.awaitTermination(3, TimeUnit.SECONDS) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun requestLocationUpdates() {
        if (!shouldRun()) return
        val manager = locationManager ?: (getSystemService(Context.LOCATION_SERVICE) as LocationManager).also { locationManager = it }
        val providers = manager.getProviders(true).filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
        for (provider in providers) {
            try {
                manager.requestLocationUpdates(provider, LOCATION_INTERVAL_MILLIS, LOCATION_MIN_DISTANCE_METERS, locationListener, Looper.getMainLooper())
            } catch (_: SecurityException) {
                // Permission can be revoked while the service is alive.
            } catch (_: IllegalArgumentException) {
                // A vendor may expose a provider without a usable listener.
            }
        }
    }

    private fun handleLocation(raw: Location) {
        if (!shouldRun()) {
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        val timestamp = raw.time.takeIf { it > 0L } ?: now
        if (timestamp > now + FUTURE_LOCATION_TOLERANCE_MILLIS || timestamp < now - MAX_LOCATION_AGE_MILLIS) return
        if (!raw.hasAccuracy() || !raw.accuracy.isFinite() || raw.accuracy > MAX_ACCURACY_METERS) return
        val previous = lastAccepted
        if (previous != null && timestamp <= previous.timestamp) return

        val elapsedSeconds = previous?.let { (timestamp - it.timestamp).coerceAtLeast(1L) / 1_000f } ?: 0f
        val distanceMeters = previous?.let { distanceBetween(it.location, raw) } ?: 0f
        if (previous != null && elapsedSeconds <= MAX_GAP_SECONDS && distanceMeters > MAX_PLAUSIBLE_SPEED_MPS * elapsedSeconds + maxOf(raw.accuracy, itAccuracy(previous.location)) * 2f) return

        val derivedSpeed = if (elapsedSeconds > 0f && elapsedSeconds <= MAX_GAP_SECONDS) distanceMeters / elapsedSeconds else 0f
        val reportedSpeed = raw.speed.takeIf { raw.hasSpeed() && it.isFinite() && it >= 0f && it <= MAX_PLAUSIBLE_SPEED_MPS }
        val speedMps = when {
            reportedSpeed == null -> derivedSpeed
            derivedSpeed <= 0f -> reportedSpeed
            abs(reportedSpeed - derivedSpeed) <= 8f -> (reportedSpeed + derivedSpeed) / 2f
            else -> derivedSpeed
        }.coerceIn(0f, MAX_PLAUSIBLE_SPEED_MPS)
        val point = TrackPoint(Location(raw), timestamp, speedMps, distanceMeters)
        lastAccepted = point
        lastLocationAt = timestamp

        val combinedAccuracy = maxOf(raw.accuracy, previous?.location?.let(::itAccuracy) ?: raw.accuracy)
        val reliableSpeed = raw.hasSpeed() && raw.accuracy <= MAX_SPEED_ACCURACY_METERS &&
            (!raw.hasSpeedAccuracy() || raw.speedAccuracyMetersPerSecond <= MAX_SPEED_ERROR_MPS)
        val startDistance = maxOf(START_DISTANCE_METERS, combinedAccuracy * START_ACCURACY_MULTIPLIER)
        val ongoingDistance = maxOf(ONGOING_DISTANCE_METERS, combinedAccuracy * ONGOING_ACCURACY_MULTIPLIER)
        val stationaryDistance = maxOf(STATIONARY_DISTANCE_METERS, combinedAccuracy * STATIONARY_ACCURACY_MULTIPLIER)
        val startEvidence = (reliableSpeed && speedMps >= START_SPEED_MPS) ||
            (distanceMeters >= startDistance && elapsedSeconds in 1f..MAX_GAP_SECONDS)
        val ongoingEvidence = (reliableSpeed && speedMps >= ONGOING_SPEED_MPS) ||
            (distanceMeters >= ongoingDistance && elapsedSeconds in 1f..MAX_GAP_SECONDS)
        val stationary = speedMps <= STATIONARY_SPEED_MPS && distanceMeters <= stationaryDistance

        when (state) {
            MotionState.IDLE -> if (startEvidence) beginCandidate(point)
            MotionState.CANDIDATE -> continueCandidate(point, startEvidence)
            MotionState.MOVING -> continueMovement(point, ongoingEvidence, stationary)
        }
        updateNotification()
    }

    private fun beginCandidate(point: TrackPoint) {
        candidatePoints.clear()
        candidatePoints.addLast(point)
        candidateEvidence = 1
        candidateStartedAt = point.timestamp
        state = MotionState.CANDIDATE
    }

    private fun continueCandidate(point: TrackPoint, evidence: Boolean) {
        if (point.timestamp - candidateStartedAt > CANDIDATE_TIMEOUT_MILLIS) {
            if (evidence) beginCandidate(point) else resetToIdle()
            return
        }
        candidatePoints.addLast(point)
        while (candidatePoints.size > MAX_CANDIDATE_POINTS) candidatePoints.removeFirst()
        if (evidence) candidateEvidence += 1
        if (candidateEvidence >= REQUIRED_START_EVIDENCE) startMovement()
    }

    private fun startMovement() {
        val first = candidatePoints.firstOrNull() ?: return
        trackId = "${first.timestamp}-${UUID.randomUUID().toString().take(8)}"
        trackStartedAt = first.timestamp
        trackLastAt = 0L
        trackDistanceMeters = 0.0
        trackMaxSpeedMps = 0f
        trackSampleCount = 0
        trackSequence = 0
        lastMovementEvidenceAt = first.timestamp
        stationarySince = 0L
        state = MotionState.MOVING
        candidatePoints.forEach(::recordTrackPoint)
        candidatePoints.clear()
        flushEvents(force = true)
    }

    private fun continueMovement(point: TrackPoint, evidence: Boolean, stationary: Boolean) {
        recordTrackPoint(point)
        if (evidence) {
            lastMovementEvidenceAt = point.timestamp
            stationarySince = 0L
        } else if (stationary) {
            if (stationarySince == 0L) stationarySince = point.timestamp
            if (point.timestamp - stationarySince >= STATIONARY_END_MILLIS || point.timestamp - lastMovementEvidenceAt >= NO_EVIDENCE_END_MILLIS) {
                finishMovement(point.timestamp)
            }
        } else if (point.timestamp - lastMovementEvidenceAt >= NO_EVIDENCE_END_MILLIS) {
            finishMovement(point.timestamp)
        }
    }

    private fun recordTrackPoint(point: TrackPoint) {
        val id = trackId ?: return
        val previousAt = trackLastAt
        if (previousAt > 0L && point.timestamp <= previousAt) return
        trackLastAt = point.timestamp
        trackDistanceMeters += point.distanceMeters.toDouble().coerceAtLeast(0.0)
        trackMaxSpeedMps = maxOf(trackMaxSpeedMps, point.speedMps)
        trackSampleCount += 1
        val sequence = trackSequence++
        synchronized(eventLock) { pendingEvents += movementPointEvent(id, sequence, point) }
        val eventCount = synchronized(eventLock) { pendingEvents.size }
        if (eventCount >= FLUSH_EVENT_COUNT || point.timestamp - flushedAt >= FLUSH_INTERVAL_MILLIS) flushEvents()
    }

    private fun finishMovement(endAt: Long) {
        if (state != MotionState.MOVING) {
            resetToIdle()
            return
        }
        val id = trackId ?: return
        val durationSeconds = ((endAt - trackStartedAt).coerceAtLeast(0L) / 1_000).toInt()
        val averageSpeed = if (durationSeconds > 0) trackDistanceMeters / durationSeconds else 0.0
        synchronized(eventLock) {
            pendingEvents += event(
                id = "SELENE-movement-session-$id",
                kind = "movement",
                startAt = iso(trackStartedAt),
                endAt = iso(endAt),
                title = "Continuous movement",
                values = JSONObject()
                    .put("trackId", id)
                    .put("durationSeconds", durationSeconds)
                    .put("distanceMeters", trackDistanceMeters.roundTo(1))
                    .put("averageSpeedMps", averageSpeed.roundTo(2))
                    .put("averageSpeedKmh", (averageSpeed * 3.6).roundTo(1))
                    .put("maxSpeedMps", trackMaxSpeedMps.toDouble().roundTo(2))
                    .put("maxSpeedKmh", (trackMaxSpeedMps * 3.6).toDouble().roundTo(1))
                    .put("sampleCount", trackSampleCount)
                    .put("detection", "location-distance-and-speed")
            )
        }
        flushEvents(force = true)
        resetToIdle()
    }

    private fun resetToIdle() {
        state = MotionState.IDLE
        candidateEvidence = 0
        candidateStartedAt = 0L
        candidatePoints.clear()
        trackId = null
        trackStartedAt = 0L
        trackLastAt = 0L
        trackDistanceMeters = 0.0
        trackMaxSpeedMps = 0f
        trackSampleCount = 0
        trackSequence = 0
        lastMovementEvidenceAt = 0L
        stationarySince = 0L
    }

    private fun movementPointEvent(trackId: String, sequence: Int, point: TrackPoint): JSONObject = event(
        id = "SELENE-movement-point-$trackId-$sequence",
        kind = "location",
        startAt = iso(point.timestamp),
        endAt = null,
        title = "Movement track point",
        values = JSONObject()
            .put("trackId", trackId)
            .put("sequence", sequence)
            .put("moving", true)
            .put("speedMps", point.speedMps.toDouble().roundTo(2))
            .put("speedKmh", (point.speedMps * 3.6).toDouble().roundTo(1))
            .put("distanceFromPreviousMeters", point.distanceMeters.toDouble().roundTo(1))
            .put("accuracyMeters", point.location.accuracy.toDouble().roundTo(1))
            .put("provider", point.location.provider ?: "unknown")
            .put("sampleMode", "foreground-service")
    ).apply {
        put("privacy", "precise")
        put("location", JSONObject()
            .put("latitude", point.location.latitude)
            .put("longitude", point.location.longitude)
            .put("accuracyMeters", point.location.accuracy)
        )
        put("locationConsent", JSONObject()
            .put("exactLocation", true)
            .put("captureMode", "foreground")
            .put("grantedAt", iso(AutoCollectionSettings.backgroundLocationConsentAt(applicationContext)))
        )
    }

    private fun flushEvents(force: Boolean = false) {
        val batch = synchronized(eventLock) {
            if (!force && pendingEvents.size < FLUSH_EVENT_COUNT) return
            if (pendingEvents.isEmpty()) return
            pendingEvents.toList().also { pendingEvents.clear() }
        }
        flushedAt = System.currentTimeMillis()
        ioExecutor.execute {
            try {
                if (ContextOutput.outputTreeUri(applicationContext) != null) ContextOutput.writeEvents(applicationContext, batch)
            } catch (_: Exception) {
                // Keep the batch for the next flush if a transient SAF error occurs.
                synchronized(eventLock) { pendingEvents.addAll(0, batch) }
            }
        }
    }

    private fun shouldRun(): Boolean = AutoCollectionSettings.isEnabled(this) &&
        AutoCollectionSettings.backgroundLocationEnabled(this) &&
        ContextOutput.outputTreeUri(this) != null &&
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "SELENE movement", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(status: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("SELENE")
        .setContentText("运动记录：$status")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setShowWhen(false)
        .build()

    private fun updateNotification() {
        val label = when (state) {
            MotionState.IDLE -> "等待移动"
            MotionState.CANDIDATE -> "确认移动中"
            MotionState.MOVING -> "记录中 ${trackMaxSpeedMps.toDouble().roundTo(1)} m/s"
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(label))
    }

    private fun distanceBetween(first: Location, second: Location): Float {
        val result = FloatArray(1)
        Location.distanceBetween(first.latitude, first.longitude, second.latitude, second.longitude, result)
        return result[0]
    }

    private fun itAccuracy(location: Location) = location.accuracy.takeIf { it.isFinite() && it > 0f } ?: MAX_ACCURACY_METERS

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

    private fun Double.roundTo(decimals: Int): Double {
        val scale = Math.pow(10.0, decimals.toDouble())
        return kotlin.math.round(this * scale) / scale
    }

    companion object {
        private const val CHANNEL_ID = "selene-movement"
        private const val NOTIFICATION_ID = 2201
        private const val LOCATION_INTERVAL_MILLIS = 15_000L
        private const val LOCATION_MIN_DISTANCE_METERS = 8f
        private const val MAX_ACCURACY_METERS = 80f
        private const val MAX_LOCATION_AGE_MILLIS = 5 * 60_000L
        private const val FUTURE_LOCATION_TOLERANCE_MILLIS = 2 * 60_000L
        private const val MAX_GAP_SECONDS = 180f
        private const val MAX_PLAUSIBLE_SPEED_MPS = 45f
        private const val MAX_SPEED_ACCURACY_METERS = 35f
        private const val MAX_SPEED_ERROR_MPS = 2.5f
        private const val START_SPEED_MPS = 0.8f
        private const val START_DISTANCE_METERS = 15f
        private const val START_ACCURACY_MULTIPLIER = 0.75f
        private const val ONGOING_SPEED_MPS = 0.65f
        private const val ONGOING_DISTANCE_METERS = 10f
        private const val ONGOING_ACCURACY_MULTIPLIER = 0.5f
        private const val STATIONARY_SPEED_MPS = 0.4f
        private const val STATIONARY_DISTANCE_METERS = 8f
        private const val STATIONARY_ACCURACY_MULTIPLIER = 0.3f
        private const val REQUIRED_START_EVIDENCE = 2
        private const val MAX_CANDIDATE_POINTS = 4
        private const val CANDIDATE_TIMEOUT_MILLIS = 90_000L
        private const val STATIONARY_END_MILLIS = 90_000L
        private const val NO_EVIDENCE_END_MILLIS = 150_000L
        private const val FLUSH_EVENT_COUNT = 24
        private const val FLUSH_INTERVAL_MILLIS = 120_000L
        private const val WATCHDOG_INTERVAL_MILLIS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, MovementTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ContextCompat.startForegroundService(context, intent)
            else context.startService(intent)
        }

        fun stop(context: Context) = context.stopService(Intent(context, MovementTrackingService::class.java))
    }
}
