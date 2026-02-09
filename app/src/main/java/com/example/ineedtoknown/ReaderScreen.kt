package com.example.ineedtoknown

import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ineedtoknown.ui.theme.AccentBlue
import com.example.ineedtoknown.ui.theme.AppBackground
import com.example.ineedtoknown.ui.theme.SurfaceDark
import com.example.ineedtoknown.ui.theme.TextGrey
import com.example.ineedtoknown.ui.theme.TextWhite
import com.github.barteksc.pdfviewer.PDFView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun ToggleButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val backgroundColor = if (isSelected) AccentBlue else Color.Transparent
    val textColor = if (isSelected) Color.White else TextGrey

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    uri: Uri,
    pdfHelper: PdfHelper,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // --- STATES ---
    var currentPage by remember { mutableIntStateOf(0) }
    var sentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentSentenceIndex by remember { mutableIntStateOf(0) }

    var isVisualMode by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var totalPages by remember { mutableIntStateOf(1) }

    // NEW: Playback Speed Control
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    val textListState = rememberLazyListState()

    // --- TTS SETUP ---
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    var isTtsReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US

                // NEW: Try to find a "High Quality" voice automatically
                val availableVoices = tts?.voices
                val bestVoice = availableVoices?.find {
                    it.name.contains("high", ignoreCase = true) && !it.name.contains("network", ignoreCase = true)
                } ?: tts?.defaultVoice

                if (bestVoice != null) {
                    tts?.voice = bestVoice
                }

                isTtsReady = true

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        currentSentenceIndex++
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
        onDispose {
            tts?.stop()
            tts?.shutdown()
        }
    }

    // NEW: Update Speed whenever variable changes
    LaunchedEffect(playbackSpeed, isTtsReady) {
        if (isTtsReady) {
            tts?.setSpeechRate(playbackSpeed)
        }
    }

    // --- AUDIO PLAYER LOGIC ---
    LaunchedEffect(isPlaying, currentSentenceIndex, isTtsReady, sentences, playbackSpeed) { // Added playbackSpeed to key
        if (isTtsReady && isPlaying) {
            if (sentences.isNotEmpty()) {
                if (currentSentenceIndex < sentences.size) {
                    tts?.setSpeechRate(playbackSpeed) // Ensure speed is set before speaking
                    tts?.speak(sentences[currentSentenceIndex], TextToSpeech.QUEUE_FLUSH, null, "id_$currentSentenceIndex")

                    if (!isVisualMode) {
                        textListState.animateScrollToItem(currentSentenceIndex)
                    }
                } else {
                    // Auto Page Turn
                    if (currentPage < totalPages - 1) {
                        currentPage++
                    } else {
                        isPlaying = false
                        currentSentenceIndex = 0
                    }
                }
            }
        } else {
            tts?.stop()
        }
    }

    // --- SYNC LOGIC (Page Turns) ---
    LaunchedEffect(currentPage, uri) {
        currentSentenceIndex = 0
        val fileName = FileNameUtils.getFileName(context, uri)
        BookStore.saveBookProgress(context, uri, currentPage, totalPages, fileName)

        withContext(Dispatchers.IO) {
            val extractedSentences = pdfHelper.extractTextFromPage(context, uri, currentPage)
            withContext(Dispatchers.Main) {
                sentences = extractedSentences
            }
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Reader", color = TextWhite, fontSize = 16.sp)
                        Text("Page ${currentPage + 1} of $totalPages", color = TextGrey, fontSize = 12.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {

            // --- MAIN CONTENT ---
            if (isVisualMode) {
                AndroidView(
                    factory = { ctx -> PDFView(ctx, null) },
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(bottom = 16.dp),
                    update = { view ->
                        view.fromUri(uri)
                            .defaultPage(currentPage)
                            .onPageChange { page, count ->
                                if (currentPage != page) currentPage = page
                                totalPages = count
                            }
                            .enableSwipe(true)
                            .swipeHorizontal(false)
                            .enableDoubletap(true)
                            .spacing(10)
                            .load()
                    }
                )
            } else {
                if (sentences.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f), contentAlignment = Alignment.Center) {
                        Text("Loading text...", color = TextGrey)
                    }
                } else {
                    LazyColumn(
                        state = textListState,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(horizontal = 16.dp)
                    ) {
                        itemsIndexed(sentences) { index, sentence ->
                            val isActive = index == currentSentenceIndex
                            val itemColor = if (isActive) AccentBlue else TextWhite
                            val bgColor = if (isActive) SurfaceDark else Color.Transparent
                            val weight = if (isActive) FontWeight.Bold else FontWeight.Normal

                            Text(
                                text = sentence,
                                color = itemColor,
                                fontSize = 18.sp,
                                lineHeight = 28.sp,
                                fontWeight = weight,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(bgColor)
                                    .clickable {
                                        currentSentenceIndex = index
                                        isPlaying = true
                                    }
                                    .padding(12.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }

            // --- BOTTOM CONTROLS ---
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(SurfaceDark, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(24.dp)
            ) {
                // Navigation
                Row(
                    modifier = Modifier.fillMaxWidth().background(AppBackground, RoundedCornerShape(12.dp)).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = SurfaceDark, contentColor = TextWhite)
                    ) { Icon(Icons.Default.Remove, "Prev") }

                    Text("${currentPage + 1} / $totalPages", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)

                    FilledTonalIconButton(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = AccentBlue, contentColor = Color.White)
                    ) { Icon(Icons.Default.Add, "Next") }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Player Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.background(Color(0xFF2D2D3A), RoundedCornerShape(8.dp)).padding(4.dp)
                    ) {
                        ToggleButton(text = "Visual", isSelected = isVisualMode) { isVisualMode = true }
                        ToggleButton(text = "Text", isSelected = !isVisualMode) { isVisualMode = false }
                    }

                    // Play Button
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(if (isTtsReady) AccentBlue else Color.Gray, CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple()
                            ) {
                                if (isTtsReady) isPlaying = !isPlaying
                                else Toast.makeText(context, "TTS Loading...", Toast.LENGTH_SHORT).show()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            "Play",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // NEW: Speed Button Logic
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                // Cycle speeds: 1.0 -> 1.5 -> 2.0 -> 0.8 -> 1.0
                                playbackSpeed = when (playbackSpeed) {
                                    1.0f -> 1.5f
                                    1.5f -> 2.0f
                                    2.0f -> 0.8f
                                    else -> 1.0f
                                }
                                Toast.makeText(context, "Speed: ${playbackSpeed}x", Toast.LENGTH_SHORT).show()
                            }
                            .padding(8.dp)
                    ) {
                        Text("${playbackSpeed}x", color = TextWhite, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}