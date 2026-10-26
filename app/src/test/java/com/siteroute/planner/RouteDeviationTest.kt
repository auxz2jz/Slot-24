package com.siteroute.planner

import org.junit.Assert.*
import org.junit.Test

class RouteDeviationTest {
    private val geometry = listOf(
        GeoPoint(36.9500, -120.1000),
        GeoPoint(36.9500, -120.0800)
    )
    private val route = RoadRoute(
        geometry = geometry, legs = emptyList(),
        distanceMeters = 1800.0, durationSeconds = 200.0
    )

    @Test fun onRoadBetweenFarApartVerticesIsNotOffRoute() {
        val center = GeoPoint(36.9500, -120.0900)
        assertEquals(0.0, RouteDeviation.metersFromRoad(center, geometry), 1.0)
        assertFalse(RouteDeviation.offRoute(route, center, 7f))
    }

    @Test fun missingTurnMoreThan60MetersFromLineTriggersOffRoute() {
        val missed = GeoPoint(36.95075, -120.0900)
        assertTrue(RouteDeviation.metersFromRoad(missed, geometry) > 70.0)
        assertTrue(RouteDeviation.offRoute(route, missed, 8f))
    }

    @Test fun noisyGpsOrNoGeometryDoesNotForceRerouting() {
        val missed = GeoPoint(36.9510, -120.0900)
        assertFalse(RouteDeviation.offRoute(route, missed, 50f))
        assertFalse(RouteDeviation.offRoute(null, missed, 5f))
        assertFalse(RouteDeviation.offRoute(route, null, 5f))
        assertFalse(RouteDeviation.offRoute(route, missed, null))
    }

    @Test fun nearbyParallelStreetNeedsDistanceBeyondAccuracyThreshold() {
        val point = GeoPoint(36.95030, -120.0900)
        assertFalse(RouteDeviation.offRoute(route, point, 4f))
        val farther = GeoPoint(36.95060, -120.0900)
        assertTrue(RouteDeviation.offRoute(route, farther, 7f))
    }

    @Test fun shortLineAndRepeatedVertexAreSupported() {
        val point = GeoPoint(36.95, -120.09)
        assertTrue(RouteDeviation.metersFromRoad(point, listOf(geometry.first())).
            isInfinite())
        assertTrue(RouteDeviation.metersFromRoad(point,
            listOf(geometry.first(), geometry.first())).isFinite())
    }
}
