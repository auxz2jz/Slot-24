package com.siteroute.planner

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.*
import kotlin.math.*

/**
 * Experimental foreground turn prompts. Only speaks near mapped maneuver coordinates
 * when the GPS fix is close to the calculated road; never treats a straight-line
 * preview as driving directions. Does not replace a certified navigation service.
 */
object VoicePromptPlanner {
    private fun meters(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians(b.latitude - a.latitude)
        val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) *
            cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 12742000.0 * asin(min(1.0, sqrt(h)))
    }

    fun prompt(route: RoadRoute, position: GeoPoint, heard: Set<String>):
        Pair<String, String>? {
        if (route.geometry.size < 2 || route.maneuvers.isEmpty()) return null
        val geometry = route.geometry
        val distances = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            distances[i] = distances[i - 1] + meters(geometry[i - 1], geometry[i])
        }
        val progress = geometry.indices.minByOrNull { meters(geometry[it], position) }
            ?: return null
        // Don't give turn commands if we've lost the road or the GPS fix is too far off.
        if (meters(geometry[progress], position) > 60.0) return null

        val upcoming = route.maneuvers.mapIndexedNotNull { index, maneuver ->
            if (maneuver.kind == "depart") return@mapIndexedNotNull null
            val point = geometry.indices.minByOrNull {
                meters(geometry[it], maneuver.location)
            } ?: return@mapIndexedNotNull null
            val remaining = distances[point] - distances[progress]
            if (remaining < -15 || remaining > 240) null
            else Triple(index, maneuver, remaining)
        }.minWithOrNull(compareBy<Triple<Int, RouteManeuver, Double>> { it.third }
            .thenBy { it.first }) ?: return null

        val (index, maneuver, remaining) = upcoming
        val close = remaining <= 40
        val key = "$index:${if (close) "turn" else "approach"}"
        if (key in heard) return null
        val words = if (close) {
            if (maneuver.kind == "arrive") "Arriving at your stop"
            else "Now, ${maneuver.instruction}"
        } else {
            val feet = ((remaining * 3.28084 / 50).roundToInt() * 50).coerceAtLeast(50)
            "In $feet feet, ${maneuver.instruction}"
        }
        return key to words
    }
}

@Composable
fun SpokenTurnGuidance(
    enabled: Boolean,
    route: RoadRoute?,
    gps: GeoPoint?,
    accuracyMeters: Float?
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var ready by remember { mutableStateOf(false) }
    val tts = remember {
        TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
        }
    }
    DisposableEffect(tts) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }
    val heard = remember(route) { mutableSetOf<String>() }
    LaunchedEffect(enabled, ready, route, gps, accuracyMeters) {
        if (enabled && ready && route != null && gps != null &&
            accuracyMeters != null && accuracyMeters <= 60f) {
            val prompt = VoicePromptPlanner.prompt(route, gps, heard)
            if (prompt != null) {
                heard.add(prompt.first)
                tts.speak(prompt.second, TextToSpeech.QUEUE_FLUSH, null,
                    "route-${prompt.first}")
            }
        } else if (!enabled) {
            tts.stop()
        }
    }
}
