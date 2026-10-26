package com.siteroute.planner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** MVP storage: local and private to this device; no account, tracking, or server. */
class SiteStore(context: Context) {
    private val preferences = context.getSharedPreferences("site_route_planner", Context.MODE_PRIVATE)

    fun load(): PlannerData {
        val text = preferences.getString("planner_data", null) ?: return PlannerData.empty()
        return runCatching {
            val root = JSONObject(text)
            val listsArray = root.getJSONArray("lists")
            val sitesArray = root.getJSONArray("sites")
            val lists = (0 until listsArray.length()).map { index ->
                val item = listsArray.getJSONObject(index)
                VisitList(item.getString("id"), item.getString("name"))
            }
            val sites = (0 until sitesArray.length()).map { index ->
                val item = sitesArray.getJSONObject(index)
                Site(
                    id = item.getString("id"),
                    listId = item.getString("listId"),
                    name = item.getString("name"),
                    address = item.optString("address", ""),
                    latitude = item.getDouble("latitude"),
                    longitude = item.getDouble("longitude"),
                    startTime = item.optString("startTime", ""),
                    endTime = item.optString("endTime", ""),
                    notes = item.optString("notes", ""),
                    position = item.optInt("position", index),
                    status = item.optString("status", "pending")
                )
            }
            if (lists.isEmpty()) PlannerData.empty() else {
                val activeId = root.optString("activeListId", lists.first().id)
                PlannerData(lists, sites, activeId.takeIf { id -> lists.any { it.id == id } }
                    ?: lists.first().id)
            }
        }.getOrElse { PlannerData.empty() }
    }

    fun save(data: PlannerData) {
        val root = JSONObject()
        root.put("activeListId", data.activeListId)
        root.put("lists", JSONArray().apply {
            data.lists.forEach { item ->
                put(JSONObject().put("id", item.id).put("name", item.name))
            }
        })
        root.put("sites", JSONArray().apply {
            data.sites.forEach { item ->
                put(JSONObject()
                    .put("id", item.id)
                    .put("listId", item.listId)
                    .put("name", item.name)
                    .put("address", item.address)
                    .put("latitude", item.latitude)
                    .put("longitude", item.longitude)
                    .put("startTime", item.startTime)
                    .put("endTime", item.endTime)
                    .put("notes", item.notes)
                    .put("position", item.position)
                    .put("status", item.status))
            }
        })
        preferences.edit().putString("planner_data", root.toString()).apply()
    }
}
