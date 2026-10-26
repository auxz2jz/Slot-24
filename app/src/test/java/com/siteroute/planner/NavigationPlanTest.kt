package com.siteroute.planner

import org.junit.Assert.*
import org.junit.Test

class NavigationPlanTest {
    private val route = VisitList("route-a", "Test")
    private val site1 = Site("a", "route-a", "First", "", 36.0, -120.0, position = 0)
    private val site2 = Site("b", "route-a", "Second", "", 36.1, -120.1, position = 1)
    private val site3 = Site("c", "route-a", "Third", "", 36.2, -120.2, position = 2)
    private val origin = GeoPoint(36.5, -120.5)

    @Test fun oneTapRoutesOnlyFromGpsToThatSite() {
        val ordered = listOf(site1, site2, site3)
        assertEquals(listOf(site3), NavigationPlan.stops(ordered, "c", true))
        assertEquals(listOf(origin, GeoPoint(36.2, -120.2)),
            NavigationPlan.points(origin, ordered, "c", true))
    }

    @Test fun fullRouteUsesSavedOrderAndExcludesCompleted() {
        val ordered = listOf(site1, site2.copy(status = "visited"), site3)
        assertEquals(listOf(site1, site3), NavigationPlan.stops(ordered, null, false))
        assertEquals(listOf(origin, GeoPoint(36.0, -120.0), GeoPoint(36.2, -120.2)),
            NavigationPlan.points(origin, ordered, null, false))
    }

    @Test fun selectedCompletedSiteStillCanBeVisitedAgain() {
        val done = site2.copy(status = "visited")
        assertEquals(listOf(done), NavigationPlan.stops(listOf(site1, done, site3), "b", true))
    }

    @Test fun moveStopChangesFullRouteOrderAndNextAfterCompletion() {
        val original = PlannerData(listOf(route), listOf(site1, site2, site3), route.id)
        val reordered = RouteLogic.moved(original, "c", -1)
        assertEquals(listOf("a", "c", "b"), RouteLogic.orderedSites(reordered).map { it.id })
        assertEquals(listOf("a", "c", "b"), NavigationPlan.stops(
            RouteLogic.orderedSites(reordered), null, false).map { it.id })
        val visited = reordered.copy(sites = reordered.sites.map {
            if (it.id == "c") it.copy(status = "visited") else it
        })
        assertEquals("b", NavigationPlan.nextPendingAfter(visited, "c")?.id)
        assertEquals("a", NavigationPlan.nextPendingAfter(visited, "b")?.id)
    }

    @Test fun selectedSiteWithoutGpsDoesNotInventCurrentPosition() {
        assertEquals(listOf(GeoPoint(36.0, -120.0)),
            NavigationPlan.points(null, listOf(site1, site2), "a", true))
    }
}
