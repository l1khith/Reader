package com.example.reader

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

    // Callback for page turn
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
                    val bestVoice = voices.find { it.name.contains("en-us-x-sfg", ignoreCase = true) }
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

    fun setSpeed(speed: Float) {
        if (isTtsReady) tts?.setSpeechRate(speed)
    }

    /**
     * Called when a new page is loaded.
     *
     * NOTIFICATION FIX (Issue #3): The notification is updated here (at page boundary) rather
     * than inside [playSentence], which would fire on every single sentence.
     */
    fun setSentences(newSentences: List<String>) {
        sentences = newSentences
        currentSentenceIndex = 0
        _currentSentenceIndexFlow.value = 0

        if (_isPlayingFlow.value && sentences.isNotEmpty()) {
            // Update the notification once for the new page, then begin speaking
            updateForegroundNotification()
            speakCurrentSentence()
        }
    }

    /**
     * Internal TTS speaker. Does NOT touch the notification — that is done only at play/pause/page boundaries.
     */
    private fun speakCurrentSentence() {
        val index = currentSentenceIndex
        if (!isTtsReady || index !in sentences.indices) return

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

    /**
     * Begin playback from a specific sentence index.
     *
     * NOTIFICATION FIX (Issue #3): [updateForegroundNotification] is only called when the play
     * state actually changes (i.e., when called from [resumeAudio]). Mid-page sentence
     * advancement does NOT trigger a notification push.
     */
    fun playSentence(index: Int) {
        if (!isTtsReady || index !in sentences.indices) return
        currentSentenceIndex = index
        _currentSentenceIndexFlow.value = index
        updateMediaState(PlaybackStateCompat.STATE_PLAYING)
        speakCurrentSentence()
    }

    fun pauseAudio() {
        tts?.stop()
        _isPlayingFlow.value = false
        updateMediaState(PlaybackStateCompat.STATE_PAUSED)

        // Update notification to show pause state, then detach from foreground
        updateForegroundNotification()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    /**
     * NOTIFICATION FIX (Issue #3): On resume, update the notification once to reflect the
     * "Playing" state, then start speaking.
     */
    fun resumeAudio() {
        _isPlayingFlow.value = true
        updateMediaState(PlaybackStateCompat.STATE_PLAYING)
        updateForegroundNotification()
        speakCurrentSentence()
    }

    private fun nextSentence() {
        if (currentSentenceIndex < sentences.size - 1) {
            currentSentenceIndex++
            _currentSentenceIndexFlow.value = currentSentenceIndex
            // No notification update here — sentence-level progression is silent
            speakCurrentSentence()
        } else {
            // End of page — notify UI to flip page
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

    /**
     * Builds and posts the foreground notification.
     * Called ONLY on: play, pause, resume, or new page — never per-sentence.
     */
    private fun updateForegroundNotification() {
        val notification = NotificationCompat.Builder(this, "READER_CHANNEL")
            .setContentTitle("Reading Book")
            .setContentText(sentences.getOrElse(currentSentenceIndex) { "Reader Active" })
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .addAction(
                android.R.drawable.ic_media_previous, "Prev",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
            )
            .addAction(
                if (_isPlayingFlow.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (_isPlayingFlow.value) "Pause" else "Play",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    if (_isPlayingFlow.value) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY
                )
            )
            .addAction(
                android.R.drawable.ic_media_next, "Next",
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
            )
            .setOngoing(_isPlayingFlow.value)
            .build()

        startForeground(1, notification)
    }

    private fun updateMediaState(state: Int) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "READER_CHANNEL",
                "Reader Playback",
                NotificationManager.IMPORTANCE_LOW
            )
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