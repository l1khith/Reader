package com.example.ineedtoknown

import android.net.Uri
import android.speech.tts.TextToSpeech
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
                indication = null // Explicitly null to prevent crash on older Compose versions
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
    var extractedText by remember { mutableStateOf("") }
    var isVisualMode by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var totalPages by remember { mutableIntStateOf(1) }

    // --- TTS SETUP ---
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    var isTtsReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isTtsReady = true
            }
        }
        onDispose {
            tts?.stop()
            tts?.shutdown()
        }
    }

    // TTS Control Logic: Speak when "isPlaying" becomes true
    LaunchedEffect(isPlaying, extractedText, isTtsReady) {
        if (isTtsReady) {
            if (isPlaying && extractedText.isNotEmpty()) {
                tts?.speak(extractedText, TextToSpeech.QUEUE_FLUSH, null, "PageText")
            } else {
                tts?.stop()
            }
        }
    }

    // --- SYNC LOGIC: Extract text when page changes ---
    LaunchedEffect(currentPage, uri) {
        // 1. Save Progress
        val fileName = FileNameUtils.getFileName(context, uri)
        BookStore.saveBookProgress(context, uri, currentPage, totalPages, fileName)

        // 2. Extract Text (Background Thread)
        withContext(Dispatchers.IO) {
            val sentences = pdfHelper.extractTextFromPage(context, uri, currentPage)
            val fullText = sentences.joinToString(". ")
            withContext(Dispatchers.Main) {
                extractedText = fullText
                // If we are currently playing, restart speech for the new page
                if (isPlaying && isTtsReady) {
                    tts?.speak(extractedText, TextToSpeech.QUEUE_FLUSH, null, "PageText")
                }
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

            // --- MAIN CONTENT (Visual or Text) ---
            if (isVisualMode) {
                // NATIVE PDF VIEW (AndroidPdfViewer)
                AndroidView(
                    factory = { ctx ->
                        PDFView(ctx, null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.75f) // Reserve space for bottom controls
                        .padding(bottom = 16.dp),
                    update = { view ->
                        view.fromUri(uri)
                            .defaultPage(currentPage)
                            .onPageChange { page, count ->
                                // Update State when user swipes
                                if (currentPage != page) {
                                    currentPage = page
                                }
                                totalPages = count
                            }
                            .enableSwipe(true)
                            .swipeHorizontal(false) // Vertical scrolling like a webpage
                            .enableDoubletap(true)
                            .spacing(10)
                            .load()
                    }
                )
            } else {
                // TEXT MODE (Simple Scroll)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.75f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    if (extractedText.isEmpty()) {
                        Text("Loading text...", color = TextGrey)
                    } else {
                        Text(text = extractedText, color = TextWhite, fontSize = 18.sp, lineHeight = 30.sp)
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
                // Page Navigation Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppBackground, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = SurfaceDark, contentColor = TextWhite
                        )
                    ) {
                        Icon(Icons.Default.Remove, "Prev")
                    }

                    Text(
                        text = "${currentPage + 1} / $totalPages",
                        color = TextWhite,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    FilledTonalIconButton(
                        onClick = { if (currentPage < totalPages - 1) currentPage++ },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = AccentBlue, contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.Add, "Next")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Player & Mode Toggle Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mode Toggle
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF2D2D3A), RoundedCornerShape(8.dp))
                            .padding(4.dp)
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
                                else Toast.makeText(context, "TTS Initializing...", Toast.LENGTH_SHORT).show()
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

                    // Spacer to balance the layout (pushes Play button to center relative to toggle)
                    Spacer(modifier = Modifier.width(48.dp))
                }
            }
        }
    }
}