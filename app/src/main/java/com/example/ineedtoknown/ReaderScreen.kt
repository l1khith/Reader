package com.example.ineedtoknown

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

@Composable
fun ToggleButton(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val backgroundColor = if (isSelected) AccentBlue else Color.Transparent
    val textColor = if (isSelected) Color.White else TextGrey

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(text, color = textColor, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
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

    // --- SERVICE CONNECTION ---
    var readerService by remember { mutableStateOf<ReaderService?>(null) }
    var isBound by remember { mutableStateOf(false) }

    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                val binder = service as ReaderService.LocalBinder
                readerService = binder.getService()
                isBound = true
            }
            override fun onServiceDisconnected(arg0: ComponentName) {
                isBound = false
                readerService = null
            }
        }
    }

    DisposableEffect(Unit) {
        val intent = Intent(context, ReaderService::class.java)
        context.startService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose {
            if (isBound) {
                context.unbindService(connection)
                isBound = false
            }
        }
    }

    // --- STATES ---

    // 1. FIX: Load Saved Page IMMEDIATELY
    val initialPage = remember {
        val savedBooks = BookStore.getAllBooks(context)
        savedBooks.find { it.uriString == uri.toString() }?.currentPage ?: 0
    }

    var currentPage by remember { mutableIntStateOf(initialPage) }
    var sentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var totalPages by remember { mutableIntStateOf(1) }
    var isVisualMode by remember { mutableStateOf(true) }

    // Speed Control State
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    // OBSERVE SERVICE STATE
    val currentSentenceIndex by readerService?.currentSentenceIndexFlow?.collectAsState(initial = 0) ?: remember { mutableIntStateOf(0) }
    val isPlaying by readerService?.isPlayingFlow?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }

    val textListState = rememberLazyListState()

    // --- SYNC SPEED TO SERVICE ---
    LaunchedEffect(playbackSpeed, readerService) {
        readerService?.setSpeed(playbackSpeed)
    }

    // --- NEW: LISTEN FOR PAGE END (Continuous Reading) ---
    // This updates the callback whenever dependencies change
    LaunchedEffect(readerService, totalPages, currentPage) {
        readerService?.onPageEndReached = {
            if (currentPage < totalPages - 1) {
                currentPage++ // This triggers the page load below
            } else {
                readerService?.pauseAudio() // End of book
            }
        }
    }

    // --- SYNC PAGE CONTENT ---
    LaunchedEffect(currentPage, uri, isBound) {
        if (!isBound) return@LaunchedEffect

        // Save progress
        val fileName = FileNameUtils.getFileName(context, uri)
        BookStore.saveBookProgress(context, uri, currentPage, totalPages, fileName)

        // Extract Text
        withContext(Dispatchers.IO) {
            val extractedSentences = pdfHelper.extractTextFromPage(context, uri, currentPage)
            withContext(Dispatchers.Main) {
                sentences = extractedSentences
                // Send text to service. Service will AUTO-PLAY if isPlaying is true.
                readerService?.setSentences(extractedSentences)
            }
        }
    }

    // Auto-Scroll Logic
    LaunchedEffect(currentSentenceIndex) {
        if (!isVisualMode && sentences.isNotEmpty()) {
            textListState.animateScrollToItem(currentSentenceIndex)
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
                            .spacing(10)
                            .load()
                    }
                )
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
                            fontWeight = weight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(bgColor)
                                .clickable { readerService?.playSentence(index) }
                                .padding(12.dp)
                        )
                    }
                }
            }

            // --- BOTTOM CONTROLS ---
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(SurfaceDark).padding(24.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledTonalIconButton(onClick = { if (currentPage > 0) currentPage-- }) {
                        Icon(Icons.Default.Remove, "Prev")
                    }
                    Text("${currentPage + 1} / $totalPages", color = TextWhite)
                    FilledTonalIconButton(onClick = { if (currentPage < totalPages - 1) currentPage++ }) {
                        Icon(Icons.Default.Add, "Next")
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.background(Color(0xFF2D2D3A), RoundedCornerShape(8.dp)).padding(4.dp)
                    ) {
                        ToggleButton(text = "Visual", isSelected = isVisualMode) { isVisualMode = true }
                        ToggleButton(text = "Text", isSelected = !isVisualMode) { isVisualMode = false }
                    }

                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(AccentBlue, CircleShape)
                            .clickable {
                                if (isPlaying) readerService?.pauseAudio()
                                else readerService?.resumeAudio()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play", tint = Color.White)
                    }

                    // Speed Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
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