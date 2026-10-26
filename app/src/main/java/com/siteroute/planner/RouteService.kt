package com.siteroute.planner

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class GeoPoint(val latitude: Double, val longitude: Double)

data class RoadLeg(
    val distanceMeters: Double,
    val durationSeconds: Double,
    val instructions: List<String>
)

data class RouteManeuver(
    val location: GeoPoint,
    val instruction: String,
    val kind: String
)

data class RoadRoute(
    val geometry: List<GeoPoint>,
    val legs: List<RoadLeg>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuvers: List<RouteManeuver> = emptyList()
)

/**
 * Prototyping only: Project OSRM's public demo is a best-effort service, not a production
 * navigation backend. Calls are made only on route/list changes or significant GPS movement.
 * No continuous high-rate polling. Replace with a dedicated routing backend for production.
 */
object RouteService {
    private const val ENDPOINT = "https://router.project-osrm.org/route/v1/driving/"

    fun buildRoute(points: List<GeoPoint>): RoadRoute {
        require(points.size in 2..25) { "Route requires 2 to 25 coordinates" }
        val coordinates = points.joinToString(";") {
            String.format(Locale.US, "%.6f,%.6f", it.longitude, it.latitude)
        }
        val url = URL(ENDPOINT + coordinates + "?overview=full&geometries=geojson&steps=true")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "SiteRoutePlanner/0.2 (personal Android route planning)")
        connection.setRequestProperty("Accept", "application/json")
        return try {
            if (connection.responseCode != 200) {
                error("Routing service unavailable (HTTP ${connection.responseCode})")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseResponse(body)
        } finally {
            connection.disconnect()
        }
    }

    fun parseResponse(json: String): RoadRoute {
        val root = JSONObject(json)
        val status = root.optString("code")
        require(status == "Ok") { "No road route found (${status.ifBlank { "unknown error" }})" }
        val route = root.getJSONArray("routes").getJSONObject(0)
        val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
        val geometry = (0 until coordinates.length()).map { i ->
            val coordinate = coordinates.getJSONArray(i)
            GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0))
        }
        require(geometry.size >= 2) { "Road routing returned no usable path" }
        val legsArray = route.getJSONArray("legs")
        val maneuverList = mutableListOf<RouteManeuver>()
        val legs = (0 until legsArray.length()).map { i ->
            val leg = legsArray.getJSONObject(i)
            val steps = leg.optJSONArray("steps")
            val instructions = if (steps == null) emptyList() else {
                (0 until steps.length()).map { n ->
                    val step = steps.getJSONObject(n)
                    val maneuver = step.getJSONObject("maneuver")
                    val location = maneuver.optJSONArray("location")
                    if (location != null && location.length() >= 2) {
                        maneuverList += RouteManeuver(
                            GeoPoint(location.getDouble(1), location.getDouble(0)),
                            formatStep(step), maneuver.optString("type")
                        )
                    }
                    formatStep(step)
                }.filter { it.isNotBlank() }
            }
            RoadLeg(
                distanceMeters = leg.optDouble("distance"),
                durationSeconds = leg.optDouble("duration"),
                instructions = instructions
            )
        }
        return RoadRoute(
            geometry = geometry,
            legs = legs,
            distanceMeters = route.getDouble("distance"),
            durationSeconds = route.getDouble("duration"),
            maneuvers = maneuverList
        )
    }

    private fun formatStep(step: JSONObject): String {
        val maneuver = step.getJSONObject("maneuver")
        val type = maneuver.optString("type").replace('_', ' ')
        val modifier = maneuver.optString("modifier")
        val street = step.optString("name").trim()
        val action = when (type) {
            "depart" -> "Start"
            "arrive" -> "Arrive at checkpoint"
            "roundabout", "rotary" -> "Enter roundabout"
            "merge" -> "Merge ${modifier}".trim()
            "fork" -> "Keep ${modifier}".trim()
            "continue", "new name" -> "Continue"
            "turn", "end of road" -> "Turn ${modifier}".trim()
            else -> type.replaceFirstChar { it.uppercase() }
        }
        return if (street.isEmpty() || type == "arrive") action else "$action onto $street"
    }

    fun miles(meters: Double): String =
        String.format(Locale.US, "%.1f mi", meters / 1609.344)

    fun minutes(seconds: Double): String =
        "${kotlin.math.ceil(seconds / 60).toInt().coerceAtLeast(1)} min"
}
