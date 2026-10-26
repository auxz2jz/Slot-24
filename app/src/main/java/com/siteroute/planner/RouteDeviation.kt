package com.siteroute.planner

import kotlin.math.*

/** Distance from a GPS fix to the closest *segment* of the road polyline, not
 * merely to its vertices (which can be hundreds of meters apart on highways).
 * Uses local tangent-plane coordinates, accurate at ordinary driving distances.
 */
object RouteDeviation {
    fun metersFromRoad(location: GeoPoint, line: List<GeoPoint>): Double {
        if (line.size < 2) return Double.POSITIVE_INFINITY
        val xScale = 111_195.0 * cos(Math.toRadians(location.latitude))
        val yScale = 111_195.0
        fun xy(point: GeoPoint): Pair<Double, Double> =
            (point.longitude - location.longitude) * xScale to
                (point.latitude - location.latitude) * yScale
        var minimumSquared = Double.POSITIVE_INFINITY
        for (index in 0 until line.lastIndex) {
            val (ax, ay) = xy(line[index])
            val (bx, by) = xy(line[index + 1])
            val dx = bx - ax
            val dy = by - ay
            val lengthSq = dx * dx + dy * dy
            val t = if (lengthSq <= 0.000001) 0.0 else
                ((-ax * dx - ay * dy) / lengthSq).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            minimumSquared = min(minimumSquared, cx * cx + cy * cy)
        }
        return sqrt(minimumSquared)
    }

    /** Conservative distance threshold avoids reroutes from noisy GPS fixes.
     * Two consecutive off-route samples are required by the caller.
     */
    fun offRoute(
        route: RoadRoute?,
        gps: GeoPoint?,
        accuracyMeters: Float?
    ): Boolean {
        if (route == null || gps == null || accuracyMeters == null ||
            !accuracyMeters.isFinite() || accuracyMeters > 35f ||
            route.geometry.size < 2) return false
        return metersFromRoad(gps, route.geometry) >
            max(45.0, accuracyMeters * 2.5)
    }

    const val CONFIRM_FIXES = 2
    const val RETRY_COOLDOWN_MS = 12_000L
}
