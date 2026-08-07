package io.github.bakahuiii.selene.context

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Low-volume, opt-in reverse geocoding for a new local place cluster.
 * Coordinates exist only in this request. Full addresses are not retained.
 */
object OnlinePlaceEnricher {
    private const val endpoint = "https://nominatim.openstreetmap.org/reverse"
    private const val userAgent = "SELENE/0.5.2 (+https://github.com/bakahuiii/SELENE)"

    fun resolve(observation: PlaceTagger.Observation): String? = runCatching {
        val requestUri = Uri.parse(endpoint).buildUpon()
            .appendQueryParameter("format", "jsonv2")
            .appendQueryParameter("lat", coordinate(observation.latitude))
            .appendQueryParameter("lon", coordinate(observation.longitude))
            .appendQueryParameter("zoom", "18")
            .appendQueryParameter("addressdetails", "0")
            .appendQueryParameter("namedetails", "1")
            .build()
        val connection = (URL(requestUri.toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            setRequestProperty("User-Agent", userAgent)
        }
        try {
            if (connection.responseCode !in 200..299) return null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                labelFor(JSONObject(reader.readText()))
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun coordinate(value: Double) = String.format(Locale.US, "%.6f", value)

    private fun labelFor(response: JSONObject): String? {
        val category = response.optString("category").ifBlank { response.optString("class") }.lowercase(Locale.ROOT)
        val type = response.optString("type").ifBlank { response.optString("addresstype") }.lowercase(Locale.ROOT)
        val categoryLabel = categoryLabel(category, type) ?: return null
        val name = preferredName(response).orEmpty().replace(Regex("[\\r\\n\\t]+"), " ").trim().take(64)
        return if (name.isBlank() || name.equals(categoryLabel, ignoreCase = true)) categoryLabel else "$categoryLabel · $name"
    }

    private fun preferredName(response: JSONObject): String? {
        val names = response.optJSONObject("namedetails")
        return listOf(
            names?.optString("name:zh"),
            names?.optString("name"),
            response.optString("name"),
        ).firstOrNull { !it.isNullOrBlank() }
    }

    private fun categoryLabel(category: String, type: String): String? = when {
        category == "amenity" && type in setOf("school", "college", "kindergarten") -> "学校"
        category == "amenity" && type == "university" -> "大学"
        category == "amenity" && type in setOf("bar", "pub", "nightclub") -> "酒吧"
        category == "amenity" && type == "cafe" -> "咖啡馆"
        category == "amenity" && type in setOf("restaurant", "fast_food", "food_court") -> "餐厅"
        category == "amenity" && type == "library" -> "图书馆"
        category == "amenity" && type in setOf("hospital", "clinic", "doctors", "pharmacy") -> "医疗场所"
        category == "amenity" && type in setOf("cinema", "theatre", "arts_centre") -> "文化场所"
        category == "leisure" && type in setOf("park", "garden", "playground") -> "公园"
        category == "shop" && type in setOf("mall", "supermarket", "convenience") -> "商店"
        category == "public_transport" && type in setOf("station", "platform") -> "交通站点"
        category == "railway" && type in setOf("station", "halt", "subway_entrance") -> "交通站点"
        category == "aeroway" && type in setOf("terminal", "aerodrome") -> "机场"
        category == "tourism" && type in setOf("museum", "gallery", "attraction") -> "景点"
        category == "building" && type in setOf("dormitory", "university") -> "宿舍"
        else -> null
    }
}
