package com.example.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class ReaderService : Service(), TextToSpeech.OnInitListener {

    private val binder = LocalBinder()
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // Single service-level scope tied to onDestroy.
    // Replaces the previous pattern of CoroutineScope(Dispatchers.Main).launch
    // called per-sentence, which created thousands of abandoned scopes during a
    // reading session and was never cancelled.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // PARTIAL_WAKE_LOCK: keeps CPU alive during TTS when screen is off (Doze Mode fix)
    private var wakeLock: PowerManager.WakeLock? = null

    private var sentences: List<String> = emptyList()
    private var currentSentenceIndex = 0

    private val _currentSentenceIndexFlow = MutableStateFlow(0)
    val currentSentenceIndexFlow: StateFlow<Int> = _currentSentenceIndexFlow

    private val _isPlayingFlow = MutableStateFlow(false)
    val isPlayingFlow: StateFlow<Boolean> = _isPlayingFlow

    // Callback for page turn
    var onPageEndReached: (() -> Unit)? = null

    private lateinit var mediaSession: MediaSessionCompat

    // Single UtteranceProgressListener instance registered ONCE in onInit.
    // The previous code re-registered a new anonymous object on every sentence,
    // which allocated garbage and could deliver callbacks to a stale listener.
    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) {
            serviceScope.launch { nextSentence() }
        }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {}
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        setupMediaSession()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReaderApp::TTSPlayLock")
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

            // Register the listener once here, not on every sentence.
            tts?.setOnUtteranceProgressListener(utteranceListener)
            isTtsReady = true
        }
    }

    fun setSpeed(speed: Float) {
        if (isTtsReady) tts?.setSpeechRate(speed)
    }

    fun setSentences(newSentences: List<String>) {
        sentences = newSentences
        currentSentenceIndex = 0
        _currentSentenceIndexFlow.value = 0

        if (_isPlayingFlow.value && sentences.isNotEmpty()) {
            updateForegroundNotification()
            speakCurrentSentence()
        }
    }

    private fun speakCurrentSentence() {
        val index = currentSentenceIndex
        if (!isTtsReady || index !in sentences.indices) return

        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "id_$index")
        tts?.speak(sentences[index], TextToSpeech.QUEUE_FLUSH, params, "id_$index")
        // No listener registration here — listener is set once in onInit.
    }

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
        mediaSession.isActive = false
        updateMediaState(PlaybackStateCompat.STATE_PAUSED)

        // Release WakeLock — CPU can sleep again when not playing
        if (wakeLock?.isHeld == true) wakeLock?.release()

        updateForegroundNotification()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    fun resumeAudio() {
        _isPlayingFlow.value = true
        mediaSession.isActive = true

        // WakeLock timeout: 4 hours covers the longest audiobook chapters.
        // The previous 10-minute timeout caused Doze Mode to kill TTS mid-sentence
        // on long reading sessions. The lock is always explicitly released in
        // pauseAudio() and onDestroy(), so the timeout is only a safety net.
        if (wakeLock?.isHeld == false) wakeLock?.acquire(4 * 60 * 60 * 1000L)

        updateMediaState(PlaybackStateCompat.STATE_PLAYING)
        updateForegroundNotification()
        speakCurrentSentence()
    }

    private fun nextSentence() {
        if (currentSentenceIndex < sentences.size - 1) {
            currentSentenceIndex++
            _currentSentenceIndexFlow.value = currentSentenceIndex
            speakCurrentSentence()
        } else {
            serviceScope.launch {
                onPageEndReached?.invoke()
            }
        }
    }

    private fun prevSentence() {
        if (currentSentenceIndex > 0) {
            playSentence(currentSentenceIndex - 1)
        }
    }

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
        val channel = NotificationChannel(
            "READER_CHANNEL",
            "Reader Playback",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        androidx.media.session.MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        mediaSession.isActive = false
        mediaSession.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        serviceScope.cancel()   // cancels all in-flight coroutines cleanly
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        fun getService(): ReaderService = this@ReaderService
    }

    override fun onBind(intent: Intent): IBinder = binder
}