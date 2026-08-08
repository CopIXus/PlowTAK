package com.atakmap.android.ideaplow.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Eyes-free voice alerts via plain Android [TextToSpeech] (no ATAK API).
 * Announcements: task received, route overdue, distress nearby. Gated by
 * the enabled preference at call time so the setting takes effect
 * immediately, and rate-limited per category so a noisy storm does not turn
 * the cab into a call center.
 */
class VoiceAlerts(
    context: Context,
    private val enabled: () -> Boolean
) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private val lastSpokenMs = HashMap<String, Long>()

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    try {
                        tts?.language = Locale.US
                    } catch (e: Exception) {
                        Log.w(TAG, "TTS language init failed", e)
                    }
                } else {
                    Log.w(TAG, "TTS init failed with status $status")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS unavailable", e)
        }
    }

    /**
     * Speak [text] unless disabled, not ready, or the same [category] spoke
     * within its rate-limit window.
     */
    fun say(category: String, text: String, minGapMs: Long = DEFAULT_GAP_MS) {
        if (!enabled() || !ready) return
        val now = System.currentTimeMillis()
        val last = lastSpokenMs[category] ?: 0L
        if (now - last < minGapMs) return
        lastSpokenMs[category] = now
        try {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "ideaplow-$category-$now")
        } catch (e: Exception) {
            Log.w(TAG, "speak failed", e)
        }
    }

    fun taskReceived(from: String) =
        say(CAT_TASK, "New task from $from. Check IdeaPlow panel.", 10_000L)

    fun routeOverdue(what: String) =
        say(CAT_OVERDUE, "$what is overdue for treatment.", 120_000L)

    fun distressNearby(callsign: String) =
        say(CAT_DISTRESS, "Distress alert from $callsign.", 30_000L)

    fun sanityPrompt(message: String) =
        say(CAT_SANITY, message, 60_000L)

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "TTS shutdown failed", e)
        }
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "IdeaPlowVoice"
        private const val DEFAULT_GAP_MS = 15_000L
        private const val CAT_TASK = "task"
        private const val CAT_OVERDUE = "overdue"
        private const val CAT_DISTRESS = "distress"
        private const val CAT_SANITY = "sanity"
    }
}
