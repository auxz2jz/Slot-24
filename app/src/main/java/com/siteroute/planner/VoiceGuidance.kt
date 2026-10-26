package com.siteroute.planner

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
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

data class NavigationSpeech(
    val tts: TextToSpeech,
    val ready: Boolean,
    val voices: List<android.speech.tts.Voice>
)

@Composable
fun rememberNavigationSpeech(): NavigationSpeech {
    val context = androidx.compose.ui.platform.LocalContext.current
    var initialized by remember { mutableStateOf(false) }
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    val tts = remember {
        TextToSpeech(context.applicationContext) { status ->
            initialized = status == TextToSpeech.SUCCESS
        }
    }
    DisposableEffect(tts) {
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }
    LaunchedEffect(initialized) {
        if (initialized) {
            val language = java.util.Locale.getDefault().language
            val available = runCatching { tts.voices.orEmpty().toList() }
                .getOrDefault(emptyList())
            val localLanguage = available.filter { it.locale.language == language }
            voices = (if (localLanguage.isNotEmpty()) localLanguage else available)
                .sortedWith(
                    compareBy<android.speech.tts.Voice> { it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality }
                        .thenBy { it.name }
                )
        }
    }
    return NavigationSpeech(tts, initialized, voices)
}

@Composable
fun SpokenTurnGuidance(
    enabled: Boolean,
    route: RoadRoute?,
    gps: GeoPoint?,
    accuracyMeters: Float?,
    speech: NavigationSpeech
) {
    val heard = remember(route) { mutableSetOf<String>() }
    LaunchedEffect(enabled, speech.ready, route, gps, accuracyMeters) {
        if (enabled && speech.ready && route != null && gps != null &&
            accuracyMeters != null && accuracyMeters <= 35f) {
            val prompt = VoicePromptPlanner.prompt(route, gps, heard)
            if (prompt != null) {
                heard.add(prompt.first)
                speech.tts.speak(prompt.second, TextToSpeech.QUEUE_FLUSH, null,
                    "route-${prompt.first}")
            }
        } else if (!enabled || route == null) {
            speech.tts.stop()
        }
    }
}

/** Only installed voices exposed by the phone's current TTS engine are listed. */
@Composable
fun NavigationVoiceSettings(
    speech: NavigationSpeech,
    selectedVoice: String,
    rate: Float,
    pitch: Float,
    onVoice: (String) -> Unit,
    onRate: (Float) -> Unit,
    onPitch: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { androidx.compose.material3.Text("Navigation voice") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                    androidx.compose.ui.unit.dp(6)
                )
            ) {
                androidx.compose.material3.Text(
                    "Select a voice installed in your Android speech engine. " +
                        "More natural voices depend on available high-quality voice downloads."
                )
                if (!speech.ready) {
                    androidx.compose.material3.Text("Initializing speech engine…")
                }
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .heightIn(max = androidx.compose.ui.unit.dp(230))
                ) {
                    item {
                        androidx.compose.material3.TextButton(
                            onClick = { onVoice("") },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                        ) { androidx.compose.material3.Text(
                            if (selectedVoice.isEmpty()) "● System default" else "○ System default"
                        ) }
                    }
                    items(speech.voices, key = { it.name }) { voice ->
                        androidx.compose.material3.TextButton(
                            onClick = { onVoice(voice.name) },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                        ) {
                            androidx.compose.material3.Text(
                                (if (selectedVoice == voice.name) "● " else "○ ") +
                                voice.name + " · " + voice.locale.toLanguageTag() +
                                (if (voice.isNetworkConnectionRequired) " (online)" else " (offline)")
                            )
                        }
                    }
                }
                androidx.compose.material3.Text("Speech speed: " +
                    String.format(java.util.Locale.US, "%.1fx", rate))
                androidx.compose.material3.Slider(value = rate, onValueChange = onRate,
                    valueRange = 0.7f..1.3f)
                androidx.compose.material3.Text("Voice pitch: " +
                    String.format(java.util.Locale.US, "%.1fx", pitch))
                androidx.compose.material3.Slider(value = pitch, onValueChange = onPitch,
                    valueRange = 0.8f..1.2f)
                androidx.compose.material3.TextButton(
                    enabled = speech.ready,
                    onClick = {
                        speech.tts.speak(
                            "In 500 feet, turn right onto Main Street.",
                            TextToSpeech.QUEUE_FLUSH, null, "voice-sample"
                        )
                    }
                ) { androidx.compose.material3.Text("▶ Preview selected voice") }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                androidx.compose.material3.Text("Done")
            }
        }
    )
}
