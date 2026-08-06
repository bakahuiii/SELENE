package io.github.bakahuiii.selene.context

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject

/** Stable, local-only clusters. Online enrichment can attach a POI label later. */
object PlaceTagger {
    private const val preferencesName = "SELENE"
    private const val clustersKey = "place-tag-clusters-v2"
    private const val nextIdKey = "place-tag-next-id"
    private const val maximumClusters = 96
    private const val defaultRadiusMeters = 250f
    private const val retryAfterMillis = 7 * 24 * 60 * 60_000L

    data class Observation(
        val id: Int,
        val tag: String,
        val latitude: Double,
        val longitude: Double,
        val needsEnrichment: Boolean,
    )

    private data class Cluster(
        val id: Int,
        val latitude: Double,
        val longitude: Double,
        val samples: Int,
        val label: String?,
        val lastEnrichmentAttemptAt: Long,
    )

    fun tag(context: Context, location: Location): String = observe(context, location).tag

    fun observe(context: Context, location: Location): Observation {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val clusters = load(preferences.getString(clustersKey, null))
        val radius = (location.accuracy.takeIf { it.isFinite() && it > 0f }?.times(2f) ?: defaultRadiusMeters)
            .coerceIn(defaultRadiusMeters, 500f)
        val matching = clusters
            .map { it to distanceMeters(location.latitude, location.longitude, it.latitude, it.longitude) }
            .filter { (_, distance) -> distance <= radius }
            .minByOrNull { (_, distance) -> distance }

        val selected = if (matching != null) {
            val cluster = matching.first
            val nextSamples = (cluster.samples + 1).coerceAtMost(10_000)
            val updated = cluster.copy(
                latitude = ((cluster.latitude * cluster.samples) + location.latitude) / nextSamples,
                longitude = ((cluster.longitude * cluster.samples) + location.longitude) / nextSamples,
                samples = nextSamples,
            )
            clusters[clusters.indexOf(cluster)] = updated
            updated
        } else if (clusters.size < maximumClusters) {
            val id = preferences.getInt(nextIdKey, 1).coerceAtLeast(1)
            val created = Cluster(id, location.latitude, location.longitude, 1, null, 0L)
            clusters += created
            preferences.edit().putInt(nextIdKey, id + 1).apply()
            created
        } else {
            clusters.minByOrNull { cluster -> distanceMeters(location.latitude, location.longitude, cluster.latitude, cluster.longitude) }
                ?: Cluster(0, location.latitude, location.longitude, 1, null, 0L)
        }
        persist(preferences, clusters)
        val now = System.currentTimeMillis()
        val neutral = neutralTag(selected.id)
        return Observation(
            id = selected.id,
            tag = selected.label ?: neutral,
            latitude = selected.latitude,
            longitude = selected.longitude,
            needsEnrichment = selected.label.isNullOrBlank() && now - selected.lastEnrichmentAttemptAt >= retryAfterMillis,
        )
    }

    fun markEnrichmentAttempt(context: Context, id: Int, attemptedAt: Long = System.currentTimeMillis()) {
        update(context, id) { it.copy(lastEnrichmentAttemptAt = attemptedAt) }
    }

    fun saveEnrichedLabel(context: Context, id: Int, label: String) {
        val normalized = label.trim().take(120)
        if (normalized.isBlank()) return
        update(context, id) { it.copy(label = normalized, lastEnrichmentAttemptAt = System.currentTimeMillis()) }
    }

    private fun update(context: Context, id: Int, transform: (Cluster) -> Cluster) {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val clusters = load(preferences.getString(clustersKey, null))
        val index = clusters.indexOfFirst { it.id == id }
        if (index < 0) return
        clusters[index] = transform(clusters[index])
        persist(preferences, clusters)
    }

    private fun neutralTag(id: Int) = "地点 ${id.toString().padStart(2, '0')}"

    private fun load(raw: String?): MutableList<Cluster> = runCatching {
        val values = JSONArray(raw ?: "[]")
        buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index) ?: continue
                val id = value.optInt("id", 0)
                val latitude = value.optDouble("latitude", Double.NaN)
                val longitude = value.optDouble("longitude", Double.NaN)
                val samples = value.optInt("samples", 1)
                val label = value.optString("label").trim().takeIf { it.isNotBlank() }
                val attemptedAt = value.optLong("lastEnrichmentAttemptAt", 0L).coerceAtLeast(0L)
                if (id > 0 && latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                    add(Cluster(id, latitude, longitude, samples.coerceAtLeast(1), label, attemptedAt))
                }
            }
        }.toMutableList()
    }.getOrDefault(mutableListOf())

    private fun persist(preferences: android.content.SharedPreferences, clusters: List<Cluster>) {
        preferences.edit().putString(clustersKey, encode(clusters)).apply()
    }

    private fun encode(clusters: List<Cluster>): String = JSONArray().also { output ->
        clusters.forEach { cluster ->
            output.put(JSONObject()
                .put("id", cluster.id)
                .put("latitude", cluster.latitude)
                .put("longitude", cluster.longitude)
                .put("samples", cluster.samples)
                .put("lastEnrichmentAttemptAt", cluster.lastEnrichmentAttemptAt)
                .apply { cluster.label?.let { put("label", it) } }
            )
        }
    }.toString()

    private fun distanceMeters(firstLatitude: Double, firstLongitude: Double, secondLatitude: Double, secondLongitude: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(firstLatitude, firstLongitude, secondLatitude, secondLongitude, result)
        return result[0]
    }
}
