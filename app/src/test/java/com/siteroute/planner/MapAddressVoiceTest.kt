package com.siteroute.planner

import org.junit.Assert.*
import org.junit.Test

class MapAddressVoiceTest {
    private val center = GeoPoint(36.9000, -120.1000)

    @Test fun addressOnlyBuildingsAreSelectable() {
        val payload = """
          {"elements":[
            {"type":"way","id":100,"center":{"lat":36.9001,"lon":-120.1001},
             "tags":{"addr:housenumber":"123","addr:street":"Oak Avenue",
                     "addr:city":"Madera","building":"house"}},
            {"type":"node","id":101,"lat":36.9002,"lon":-120.1002,
             "tags":{"name":"Walmart","shop":"supermarket","addr:street":"Main St"}}
          ]}
        """.trimIndent()
        val result = NearbyPlaces.parseOverpass(payload, center)
        assertEquals(2, result.size)
        val house = result.first { it.id == "osm-way-100" }
        assertEquals("123 Oak Avenue, Madera", house.name)
        assertEquals("123 Oak Avenue, Madera", house.address)
        assertEquals("Street address", house.category)
    }

    @Test fun mapTapPreservesChosenPoint() {
        val touched = GeoPoint(36.9002, -120.1003)
        val place = NearbyPlaces.mapTapOption(touched)
        assertEquals("tap-point", place.id)
        assertEquals(touched, place.location)
    }

    @Test fun voicePromptsOnlyNearCalculatedRoadAndNotRepeated() {
        val route = RoadRoute(
            geometry = listOf(
                GeoPoint(36.9, -120.1),
                GeoPoint(36.901, -120.1),
                GeoPoint(36.902, -120.1)
            ),
            legs = emptyList(), distanceMeters = 220.0, durationSeconds = 30.0,
            maneuvers = listOf(
                RouteManeuver(GeoPoint(36.902, -120.1), "Turn right onto Main Street", "turn")
            )
        )
        val prompt = VoicePromptPlanner.prompt(route, center, emptySet())
        assertNotNull(prompt)
        assertTrue(prompt!!.second.contains("Turn right onto Main Street"))
        assertNull(VoicePromptPlanner.prompt(route, center, setOf(prompt.first)))
        assertNull(VoicePromptPlanner.prompt(route,
            GeoPoint(35.0, -120.1), emptySet()))
    }
}
