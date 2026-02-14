package com.example.ineedtoknown

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class ReaderService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var sentences: List<String> = emptyList()
    private var currentSentenceIndex = 0

    private val _currentSentenceIndexFlow = MutableStateFlow(0)
    val currentSentenceIndexFlow: StateFlow<Int> = _currentSentenceIndexFlow

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

    // --- NEW: Callback for Page Turn ---
    var onPageEndReached: (() -> Unit)? = null

    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        setupMediaSession()
        createNotificationChannel()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "ReaderService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resumeAudio() }
                override fun onPause() { pauseAudio() }
                override fun onSkipToNext() { nextSentence() }
                override fun onSkipToPrevious() { prevSentence() }
            })
            isActive = true
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)

            // SMART VOICE HUNTER
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                val voices = tts?.voices
                if (voices != null) {
                    val bestVoice = voices.find { it.name.contains("en-us-x-sfg", ignoreCase = true) } // Voice IV (Best Female)
                        ?: voices.find { it.name.contains("en-us-x-tpf", ignoreCase = true) }
                        ?: voices.find { it.name.contains("high", ignoreCase = true) }
                        ?: voices.find { it.name.contains("enhanced", ignoreCase = true) }
                        ?: tts?.defaultVoice

                    if (bestVoice != null) tts?.voice = bestVoice
                }
            }
            isTtsReady = true
        }
    }

    // SPEED CONTROL
    fun setSpeed(speed: Float) {
        if (isTtsReady) {
            tts?.setSpeechRate(speed)
        }
    }

    // --- UPDATED: Handle Auto-Play for New Pages ---
    fun setSentences(newSentences: List<String>) {
        sentences = newSentences
        currentSentenceIndex = 0
        _currentSentenceIndexFlow.value = 0

        // IMPORTANT: If we are currently "Playing" (even if waiting for page turn),
        // start reading the new page immediately.
        if (_isPlayingFlow.value && sentences.isNotEmpty()) {
            playSentence(0)
        }
    }

    fun playSentence(index: Int) {
        if (!isTtsReady || index !in sentences.indices) return

        currentSentenceIndex = index
        _currentSentenceIndexFlow.value = index
        _isPlayingFlow.value = true

        updateMediaState(PlaybackStateCompat.STATE_PLAYING)
        startForegroundService()

        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "id_$index")
        tts?.speak(sentences[index], TextToSpeech.QUEUE_FLUSH, params, "id_$index")

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                CoroutineScope(Dispatchers.Main).launch { nextSentence() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {}
        })
    }

    fun pauseAudio() {
        tts?.stop()
        _isPlayingFlow.value = false
        updateMediaState(PlaybackStateCompat.STATE_PAUSED)
        stopForeground(false)
    }

    fun resumeAudio() {
        playSentence(currentSentenceIndex)
    }

    // --- UPDATED: Trigger Page Turn Logic ---
    private fun nextSentence() {
        if (currentSentenceIndex < sentences.size - 1) {
            playSentence(currentSentenceIndex + 1)
        } else {
            // End of Page Reached.
            // Do NOT pause. We want to keep 'isPlaying' true so the next page starts.
            // Notify the UI to flip the page.
            CoroutineScope(Dispatchers.Main).launch {
                onPageEndReached?.invoke()
            }
        }
    }

    private fun prevSentence() {
        if (currentSentenceIndex > 0) {
            playSentence(currentSentenceIndex - 1)
        }
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, "READER_CHANNEL")
            .setContentTitle("Reading Book")
            .setContentText(sentences.getOrElse(currentSentenceIndex) { "Reader Active" })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Prev",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(if (_isPlayingFlow.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (_isPlayingFlow.value) "Pause" else "Play",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, if (_isPlayingFlow.value) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY))
            .addAction(android.R.drawable.ic_media_next, "Next",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun updateMediaState(state: Int) {
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("READER_CHANNEL", "Reader Playback", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        mediaSession.release()
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ReaderService = this@ReaderService
    }
    override fun onBind(intent: Intent): IBinder = binder
}