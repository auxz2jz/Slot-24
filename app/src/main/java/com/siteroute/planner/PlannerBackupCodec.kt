package com.siteroute.planner

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/**
 * Portable manual backup. Nothing is uploaded by this app: users choose the file location
 * via Android's document picker (local storage, Drive, or another document provider).
 * Validation completes before any saved local data is replaced.
 */
object PlannerBackupCodec {
    const val MIME = "application/json"
    const val FILE_PREFIX = "site-route-planner"
    private const val FORMAT = "site-route-planner-backup"
    private const val VERSION = 1
    private const val MAX_LISTS = 1000
    private const val MAX_SITES = 20000
    const val MAX_BYTES = 8 * 1024 * 1024

    fun encode(data: PlannerData): String {
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("activeListId", data.activeListId)
            .put("lists", JSONArray().apply {
                data.lists.forEach { put(JSONObject()
                    .put("id", it.id).put("name", it.name)) }
            })
            .put("sites", JSONArray().apply {
                data.sites.forEach {
                    put(JSONObject()
                        .put("id", it.id)
                        .put("listId", it.listId)
                        .put("name", it.name)
                        .put("address", it.address)
                        .put("latitude", it.latitude)
                        .put("longitude", it.longitude)
                        .put("startTime", it.startTime)
                        .put("endTime", it.endTime)
                        .put("notes", it.notes)
                        .put("position", it.position)
                        .put("status", it.status))
                }
            })
        return root.toString(2)
    }

    fun decode(json: String): PlannerData {
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "Backup file is too large (maximum 8 MB)."
        }
        try {
            val root = JSONObject(json)
            require(root.getString("format") == FORMAT) {
                "This isn't a Site Route Planner backup."
            }
            require(root.getInt("version") == VERSION) {
                "Unsupported backup version."
            }
            val listItems = root.getJSONArray("lists")
            val siteItems = root.getJSONArray("sites")
            require(listItems.length() in 1..MAX_LISTS && siteItems.length() <= MAX_SITES) {
                "The backup contains an invalid number of lists or stops."
            }
            val lists = (0 until listItems.length()).map { i ->
                val row = listItems.getJSONObject(i)
                VisitList(id = requiredString(row, "id", 200),
                    name = requiredString(row, "name", 300))
            }
            require(lists.map { it.id }.toSet().size == lists.size) {
                "The backup contains repeated list identifiers."
            }
            val listIds = lists.map { it.id }.toSet()
            val sites = (0 until siteItems.length()).map { i ->
                val row = siteItems.getJSONObject(i)
                val id = requiredString(row, "id", 200)
                val listId = requiredString(row, "listId", 200)
                require(listId in listIds) { "Stop refers to a missing list." }
                val latitude = row.getDouble("latitude")
                val longitude = row.getDouble("longitude")
                require(latitude.isFinite() && latitude in -90.0..90.0 &&
                    longitude.isFinite() && longitude in -180.0..180.0) {
                    "Invalid coordinates in backup."
                }
                val status = row.getString("status")
                require(status in setOf("pending", "visited", "skipped")) {
                    "Invalid stop progress in backup."
                }
                val start = optionalString(row, "startTime", 10)
                val end = optionalString(row, "endTime", 10)
                for (value in listOf(start, end)) {
                    require(value.isBlank() || runCatching {
                        LocalTime.parse(value).toString().take(5) == value
                    }.getOrDefault(false)) { "Invalid scheduled visit time in backup." }
                }
                require(start.isBlank() || end.isBlank() || start <= end) {
                    "Invalid scheduled visit window in backup."
                }
                val position = row.getInt("position")
                require(position >= 0) { "Invalid checkpoint ordering in backup." }
                Site(
                    id = id,
                    listId = listId,
                    name = requiredString(row, "name", 300),
                    address = optionalString(row, "address", 2500),
                    latitude = latitude,
                    longitude = longitude,
                    startTime = start,
                    endTime = end,
                    notes = optionalString(row, "notes", 10000),
                    position = position,
                    status = status
                )
            }
            require(sites.map { it.id }.toSet().size == sites.size) {
                "The backup contains repeated stop identifiers."
            }
            val activeId = root.getString("activeListId")
            require(activeId in listIds) { "The active list is missing from the backup." }
            return PlannerData(lists = lists, sites = sites, activeListId = activeId)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            throw IllegalArgumentException("Backup is invalid or damaged.")
        }
    }

    private fun requiredString(row: JSONObject, key: String, max: Int): String {
        val value = row.getString(key)
        require(value.isNotBlank() && value.length <= max) { "Invalid $key in backup." }
        return value
    }

    private fun optionalString(row: JSONObject, key: String, max: Int): String {
        val value = row.optString(key, "")
        require(value.length <= max) { "Invalid $key in backup." }
        return value
    }
}
