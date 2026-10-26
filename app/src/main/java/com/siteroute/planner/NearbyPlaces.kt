package com.siteroute.planner

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import kotlin.math.*

data class NearbyPlace(
    val id: String,
    val name: String,
    val address: String,
    val location: GeoPoint,
    val category: String,
    val metersFromGps: Int
)

data class NearbySearchResult(
    val places: List<NearbyPlace>,
    val warning: String?
)

/**
 * A user-initiated location lookup. Business names come from OpenStreetMap/Overpass,
 * and nearby street addresses come from the device's geocoder.
 * These public services are best-effort, may lack businesses, and aren't a
 * production-scale places service. Do not make requests on every GPS change.
 */
object NearbyPlaces {
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"
    private const val MAX_RESPONSE = 2 * 1024 * 1024

    fun gpsOption(point: GeoPoint): NearbyPlace =
        NearbyPlace(
            id = "gps",
            name = "Exact GPS position",
            address = "",
            location = point,
            category = "Your position",
            metersFromGps = 0
        )

    /** Reverse-geocode the exact point the user tapped. */
    suspend fun atMapTap(context: Context, tapped: GeoPoint): List<NearbyPlace> =
        withContext(Dispatchers.IO) {
            reverseGeocode(context, tapped).mapIndexed { index, candidate ->
                candidate.copy(id = "tap-address-$index")
            }.filter { it.metersFromGps <= 180 }
        }

    fun mapTapOption(tapped: GeoPoint): NearbyPlace =
        gpsOption(tapped).copy(id = "tap-point", name = "Exact tapped map point",
            category = "Map coordinate")

    suspend fun search(context: Context, gps: GeoPoint): NearbySearchResult =
        withContext(Dispatchers.IO) {
            val streetCandidates = reverseGeocode(context, gps)
            val businesses = runCatching { queryBusinesses(gps) }
            val warning = businesses.exceptionOrNull()?.let {
                "Nearby businesses could not be loaded; GPS and available street addresses still work."
            }
            val all = (businesses.getOrDefault(emptyList()) + streetCandidates)
                .filter { it.metersFromGps <= 650 }
                .distinctBy {
                    "${it.name.lowercase(Locale.US)}:${(it.location.latitude * 10000).roundToInt()}:" +
                        "${(it.location.longitude * 10000).roundToInt()}"
                }
                .sortedWith(compareBy<NearbyPlace> { it.metersFromGps }.thenBy { it.name })
                .take(24)
            NearbySearchResult(all, warning)
        }

    private fun reverseGeocode(context: Context, gps: GeoPoint): List<NearbyPlace> {
        return runCatching {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault())
                .getFromLocation(gps.latitude, gps.longitude, 7).orEmpty()
            addresses.mapIndexedNotNull { index, address ->
                val display = address.getAddressLine(0)?.trim().orEmpty()
                if (display.isBlank()) return@mapIndexedNotNull null
                val loc = GeoPoint(address.latitude, address.longitude)
                NearbyPlace(
                    id = "address-$index",
                    name = display,
                    address = display,
                    location = loc,
                    category = "Nearby street address",
                    metersFromGps = meters(gps, loc)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun queryBusinesses(gps: GeoPoint): List<NearbyPlace> {
        // At most one small, explicit request when the user presses Find Nearby.
        val lat = String.format(Locale.US, "%.6f", gps.latitude)
        val lon = String.format(Locale.US, "%.6f", gps.longitude)
        val q = """[out:json][timeout:15];(
            nwr(around:450,$lat,$lon)["name"]["shop"];
            nwr(around:450,$lat,$lon)["name"]["amenity"];
            nwr(around:450,$lat,$lon)["name"]["tourism"];
            nwr(around:450,$lat,$lon)["name"]["office"];
            nwr(around:450,$lat,$lon)["name"]["leisure"];
            nwr(around:250,$lat,$lon)["addr:housenumber"]["addr:street"];
        );out center 350;""".trimIndent()
        val url = URL(OVERPASS)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 13000
            readTimeout = 19000
            setRequestProperty("User-Agent", "SiteRoutePlanner-Android/0.6 (manual nearby place search)")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val body = ("data=" + URLEncoder.encode(q, "UTF-8"))
                .toByteArray(Charsets.UTF_8)
            connection.outputStream.use { it.write(body) }
            check(connection.responseCode == 200) {
                "Nearby lookup HTTP ${connection.responseCode}"
            }
            val data = connection.inputStream.use { input ->
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(bytes.size() + n <= MAX_RESPONSE) {
                        "Nearby search response too large"
                    }
                    bytes.write(buffer, 0, n)
                }
                bytes.toString("UTF-8")
            }
            parseOverpass(data, gps)
        } finally {
            connection.disconnect()
        }
    }

    fun parseOverpass(json: String, gps: GeoPoint): List<NearbyPlace> {
        val elements = JSONObject(json).getJSONArray("elements")
        return (0 until elements.length()).mapNotNull { index ->
            val element = elements.optJSONObject(index) ?: return@mapNotNull null
            val tags = element.optJSONObject("tags") ?: return@mapNotNull null
            val addr = addressOf(tags)
            val name = tags.optString("name").trim().ifBlank { addr }
            if (name.isBlank()) return@mapNotNull null
            val center = element.optJSONObject("center")
            val lat = if (element.has("lat")) element.optDouble("lat")
                else center?.optDouble("lat") ?: Double.NaN
            val lon = if (element.has("lon")) element.optDouble("lon")
                else center?.optDouble("lon") ?: Double.NaN
            if (!lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            val point = GeoPoint(lat, lon)
            val address = addr
            val category = sequenceOf("shop", "amenity", "tourism", "office", "leisure")
                .map { tags.optString(it) }.firstOrNull { it.isNotBlank() } ?: "Street address"
            NearbyPlace(
                id = "osm-${element.optString("type")}-${element.optLong("id")}",
                name = name,
                address = address,
                location = point,
                category = category.replace('_', ' '),
                metersFromGps = meters(gps, point)
            )
        }.filter { it.metersFromGps <= 650 }
            .distinctBy { it.id }
            .sortedWith(compareBy<NearbyPlace> { it.metersFromGps }.thenBy { it.name })
    }

    private fun addressOf(tags: JSONObject): String {
        val street = listOf(tags.optString("addr:housenumber"),
            tags.optString("addr:street")).filter { it.isNotBlank() }.joinToString(" ")
        return listOf(street, tags.optString("addr:city"),
            tags.optString("addr:state"), tags.optString("addr:postcode"))
            .filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun meters(from: GeoPoint, to: GeoPoint): Int {
        val r = 6371000.0
        val dlat = Math.toRadians(to.latitude - from.latitude)
        val dlon = Math.toRadians(to.longitude - from.longitude)
        val a = sin(dlat / 2).pow(2) +
            cos(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) *
            sin(dlon / 2).pow(2)
        return (2 * r * asin(min(1.0, sqrt(a)))).roundToInt().coerceAtLeast(0)
    }
}
