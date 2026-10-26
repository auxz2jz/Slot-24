package com.siteroute.planner

import java.time.LocalTime
import java.time.format.DateTimeFormatter

object RouteLogic {
    private val displayTime = DateTimeFormatter.ofPattern("HH:mm")
    private val inputTime = DateTimeFormatter.ofPattern("H:mm")

    fun normalizeTime(raw: String): String? {
        if (raw.isBlank()) return ""
        return runCatching {
            LocalTime.parse(raw.trim(), inputTime).format(displayTime)
        }.getOrNull()
    }

    fun orderedSites(data: PlannerData): List<Site> =
        data.sites.filter { it.listId == data.activeListId }
            .sortedWith(compareBy<Site> { it.position }.thenBy { it.id })

    fun nextPending(data: PlannerData): Site? =
        orderedSites(data).firstOrNull { it.status == "pending" }

    fun timeDescription(site: Site): String = when {
        site.startTime.isNotBlank() && site.endTime.isNotBlank() &&
            site.startTime == site.endTime -> "Visit at " + site.startTime
        site.startTime.isNotBlank() && site.endTime.isNotBlank() ->
            "Visit " + site.startTime + "–" + site.endTime
        site.startTime.isNotBlank() -> "Not before " + site.startTime
        site.endTime.isNotBlank() -> "By " + site.endTime
        else -> "No scheduled time"
    }

    // MVP assumes every window is within one calendar day (no midnight-spanning windows).
    fun timeWarning(site: Site, now: LocalTime = LocalTime.now()): String? {
        if (site.status != "pending") return null
        val start = site.startTime.takeIf { it.isNotBlank() }?.let(LocalTime::parse)
        val end = site.endTime.takeIf { it.isNotBlank() }?.let(LocalTime::parse)
        return when {
            end != null && now.isAfter(end) -> "Past scheduled latest time"
            start != null && now.isBefore(start) -> "Before scheduled start"
            else -> null
        }
    }

    fun moved(data: PlannerData, id: String, delta: Int): PlannerData {
        val ordered = orderedSites(data).toMutableList()
        val index = ordered.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in ordered.indices) return data
        val current = ordered[index]
        ordered[index] = ordered[target]
        ordered[target] = current
        val newPositions = ordered.mapIndexed { n, site -> site.id to n }.toMap()
        return data.copy(sites = data.sites.map { site ->
            newPositions[site.id]?.let { site.copy(position = it) } ?: site
        })
    }
}
