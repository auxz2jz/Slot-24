package com.siteroute.planner

import org.junit.Assert.*
import org.junit.Test

class NearbyPlacesTest {
    private val gps = GeoPoint(36.9100, -120.1200)

    @Test fun parsesNearbyNamedBusinessIncludingAddressAndCentroid() {
        val response = """
          {"elements":[
            {"type":"way","id":55,
             "center":{"lat":36.9104,"lon":-120.1205},
             "tags":{"name":"Walmart","shop":"supermarket","addr:housenumber":"123",
                     "addr:street":"Main Street","addr:city":"Dos Palos"}},
            {"type":"node","id":56,"lat":36.9101,"lon":-120.1201,
             "tags":{"name":"Coffee Shop","amenity":"cafe"}},
            {"type":"node","id":57,"lat":36.0,"lon":-119.0,
             "tags":{"name":"Too far away","shop":"yes"}}
          ]}
        """.trimIndent()
        val found = NearbyPlaces.parseOverpass(response, gps)
        assertEquals(2, found.size)
        assertEquals("Coffee Shop", found.first().name)
        val walmart = found.first { it.name == "Walmart" }
        assertEquals("osm-way-55", walmart.id)
        assertEquals("123 Main Street, Dos Palos", walmart.address)
        assertEquals("supermarket", walmart.category)
        assertEquals(36.9104, walmart.location.latitude, 0.00001)
        assertTrue(walmart.metersFromGps > 0)
    }

    @Test fun dropsUnnamedAndUnlocatedMapEntries() {
        val response = """
          {"elements":[
             {"type":"node","id":1,"lat":36.9,"lon":-120.12,
              "tags":{"shop":"supermarket"}},
             {"type":"way","id":2,"tags":{"name":"Unnamed coordinate issue","shop":"yes"}}
           ]}
        """.trimIndent()
        assertTrue(NearbyPlaces.parseOverpass(response, gps).isEmpty())
    }

    @Test fun exactGpsAlwaysMatchesCurrentPosition() {
        val direct = NearbyPlaces.gpsOption(gps)
        assertEquals("gps", direct.id)
        assertEquals(gps, direct.location)
        assertEquals(0, direct.metersFromGps)
    }
}
