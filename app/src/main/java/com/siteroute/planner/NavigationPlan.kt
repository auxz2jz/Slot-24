package com.siteroute.planner

/**
 * The whole-list overview and point-to-point navigation are deliberately separate.
 * Choosing any row creates only the GPS-to-that-checkpoint leg. Returning to full route
 * draws the remaining pending checkpoints in the exact saved order.
 */
object NavigationPlan {
    fun stops(all: List<Site>, selectedId: String?, direct: Boolean): List<Site> =
        if (direct) all.firstOrNull { it.id == selectedId }?.let(::listOf).orEmpty()
        else all.filter { it.status == "pending" }

    fun points(origin: GeoPoint?, all: List<Site>, selectedId: String?, direct: Boolean): List<GeoPoint> {
        val stops = stops(all, selectedId, direct)
        return (origin?.let(::listOf).orEmpty() +
            stops.map { GeoPoint(it.latitude, it.longitude) })
    }

    fun nextPendingAfter(data: PlannerData, completedId: String): Site? {
        val all = RouteLogic.orderedSites(data)
        val index = all.indexOfFirst { it.id == completedId }
        if (index < 0) return all.firstOrNull { it.status == "pending" }
        return (all.drop(index + 1) + all.take(index + 1))
            .firstOrNull { it.status == "pending" }
    }
}
