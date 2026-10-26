package com.siteroute.planner

import org.junit.Assert.*
import org.junit.Test

class PlannerBackupCodecTest {
    private val morning = VisitList("morning", "My Stops")
    private val afternoon = VisitList("afternoon", "Afternoon calls")

    private val original = PlannerData(
        lists = listOf(morning, afternoon),
        sites = listOf(
            Site(
                id = "stop-1", listId = "morning", name = "Pump station",
                address = "100 Main St", latitude = 36.91, longitude = -120.12,
                startTime = "08:00", endTime = "09:30", notes = "Use side gate",
                position = 0, status = "visited"
            ),
            Site(
                id = "stop-2", listId = "morning", name = "Checkpoint B",
                address = "", latitude = 36.94, longitude = -120.18,
                position = 1, status = "pending"
            ),
            Site(
                id = "stop-3", listId = "afternoon", name = "Site C",
                address = "Warehouse", latitude = 36.99, longitude = -120.2,
                position = 0, status = "skipped"
            )
        ),
        activeListId = "afternoon"
    )

    @Test fun roundTripPreservesEveryListStopStatusOrderAndNotes() {
        val exported = PlannerBackupCodec.encode(original)
        assertTrue(exported.contains("site-route-planner-backup"))
        val restored = PlannerBackupCodec.decode(exported)
        assertEquals(original, restored)
        assertEquals("Use side gate", restored.sites.first().notes)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsWrongBackupFormat() {
        PlannerBackupCodec.decode("""{"format":"something-else","version":1,"lists":[],"sites":[]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsStopWhoseListDoesNotExist() {
        PlannerBackupCodec.decode(PlannerBackupCodec.encode(original)
            .replace("stop-1", "stop-1").replace("\"listId\": \"morning\"", "\"listId\": \"gone\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidCoordinates() {
        PlannerBackupCodec.decode(PlannerBackupCodec.encode(original).replace(
            "\"latitude\": 36.91", "\"latitude\": 999.0"
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlargeFile() {
        PlannerBackupCodec.decode("A".repeat(PlannerBackupCodec.MAX_BYTES + 1))
    }
}
