package com.siteroute.planner

import org.junit.Assert.*
import org.junit.Test

class RouteServiceTest {
    @Test fun parsesMultipleCheckpointRoadRouteAndDirections() {
        val response = """
        {
          "code": "Ok",
          "routes": [{
            "distance": 3200,
            "duration": 300,
            "geometry": {
              "type": "LineString",
              "coordinates": [[-120.1, 36.9], [-120.11, 36.91], [-120.13, 36.93]]
            },
            "legs": [
              {
                "distance": 1200, "duration": 100,
                "steps": [
                  {"name": "Main Street", "maneuver": {"type": "turn", "modifier": "right"}},
                  {"name": "", "maneuver": {"type": "arrive"}}
                ]
              },
              {"distance": 2000, "duration": 200, "steps": []}
            ]
          }]
        }
        """.trimIndent()
        val route = RouteService.parseResponse(response)
        assertEquals(3, route.geometry.size)
        assertEquals(-120.1, route.geometry.first().longitude, 0.0001)
        assertEquals(2, route.legs.size)
        assertEquals("Turn right onto Main Street", route.legs.first().instructions.first())
        assertEquals("Arrive at checkpoint", route.legs.first().instructions.last())
        assertEquals("2.0 mi", RouteService.miles(route.distanceMeters))
        assertEquals("5 min", RouteService.minutes(route.durationSeconds))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsResponseWithoutRoute() {
        RouteService.parseResponse("""{"code":"NoRoute"}""")
    }
}
