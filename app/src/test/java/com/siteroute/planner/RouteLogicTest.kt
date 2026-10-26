package com.siteroute.planner

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class RouteLogicTest {
    private val a = VisitList(id = "a", name = "Route A")
    private val b = VisitList(id = "b", name = "Route B")
    private fun site(id: String, list: String, position: Int, status: String = "pending") =
        Site(id = id, listId = list, name = id, address = "",
            latitude = 36.0, longitude = -120.0, position = position, status = status)

    @Test fun nextPendingUsesActiveListAndManualOrder() {
        val data = PlannerData(listOf(a, b),
            listOf(site("third", "a", 2), site("first", "a", 0, "visited"),
                site("second", "a", 1), site("foreign", "b", 0)), a.id)
        assertEquals("second", RouteLogic.nextPending(data)?.id)
        assertEquals("foreign", RouteLogic.nextPending(data.copy(activeListId = b.id))?.id)
    }

    @Test fun skipAndCompletedNotSelected() {
        val data = PlannerData(listOf(a),
            listOf(site("one", "a", 0, "skipped"), site("two", "a", 1, "visited")), a.id)
        assertNull(RouteLogic.nextPending(data))
    }

    @Test fun movingStopUpdatesOrder() {
        val data = PlannerData(listOf(a),
            listOf(site("one", "a", 0), site("two", "a", 1)), a.id)
        val moved = RouteLogic.moved(data, "two", -1)
        assertEquals("two", RouteLogic.orderedSites(moved).first().id)
    }

    @Test fun timeValidationAndWarnings() {
        assertEquals("09:05", RouteLogic.normalizeTime("9:05"))
        assertNull(RouteLogic.normalizeTime("31:99"))
        val s = site("one", "a", 0).copy(startTime = "09:00", endTime = "10:00")
        assertEquals("Before scheduled start",
            RouteLogic.timeWarning(s, LocalTime.of(8, 30)))
        assertEquals("Past scheduled latest time",
            RouteLogic.timeWarning(s, LocalTime.of(10, 30)))
    }
}
