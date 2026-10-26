package com.siteroute.planner

import java.util.UUID

data class VisitList(
    val id: String = UUID.randomUUID().toString(),
    val name: String
)

data class Site(
    val id: String = UUID.randomUUID().toString(),
    val listId: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val startTime: String = "",
    val endTime: String = "",
    val notes: String = "",
    val position: Int = 0,
    val status: String = "pending"
)

data class PlannerData(
    val lists: List<VisitList>,
    val sites: List<Site>,
    val activeListId: String
) {
    companion object {
        fun empty(): PlannerData {
            val first = VisitList(name = "My Stops")
            return PlannerData(listOf(first), emptyList(), first.id)
        }
    }
}
