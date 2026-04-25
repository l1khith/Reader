package com.example.reader

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
import com.example.reader.ui.theme.AccentBlue
import com.example.reader.ui.theme.AppBackground
import com.example.reader.ui.theme.SurfaceDark
import com.example.reader.ui.theme.TextGrey
import com.example.reader.ui.theme.TextWhite
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

    // ─────────────────────────────────────────────────────────────────────────
    // SERVICE CONNECTION
    // ─────────────────────────────────────────────────────────────────────────

    var readerService by remember { mutableStateOf<ReaderService?>(null) }
    var isBound by remember { mutableStateOf(false) }

    val serviceIntent = remember { Intent(context, ReaderService::class.java) }

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

    // ─────────────────────────────────────────────────────────────────────────
    // SERVICE + PDF LIFECYCLE (Issues #2, #5, #6)
    //
    // We open both the PDDocument and PdfRenderer once here, and clean them up
    // in onDispose. We also stop the service if the user exits while paused
    // (Issue #5) to prevent the service from lingering indefinitely.
    // ─────────────────────────────────────────────────────────────────────────

    DisposableEffect(uri) {
        // Open PDF resources (Issues #2 and #6)
        pdfHelper.openDocument(context, uri)
        pdfHelper.openRenderer(context, uri)

        // Start and bind service
        context.startService(serviceIntent)
        context.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        onDispose {
            // Unbind service
            if (isBound) {
                context.unbindService(connection)
            }

            // Issue #5 fix: If the user backs out while paused (or never started playing),
            // explicitly stop the started service so it doesn't linger.
            val isCurrentlyPlaying = readerService?.isPlayingFlow?.value ?: false
            if (!isCurrentlyPlaying) {
                context.stopService(serviceIntent)
            }

            // Release PDF resources (Issues #2 and #6)
            pdfHelper.closeDocument()
            pdfHelper.closeRenderer()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STATES
    // ─────────────────────────────────────────────────────────────────────────

    val initialPage = remember {
        val savedBooks = BookStore.getAllBooksFlow(context)
        // Can't suspend here; use page 0 as default, the LaunchedEffect below will restore
        0
    }

    var currentPage by remember { mutableIntStateOf(initialPage) }
    var sentences by remember { mutableStateOf<List<String>>(emptyList()) }
    var totalPages by remember { mutableIntStateOf(1) }
    var isVisualMode by remember { mutableStateOf(true) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    // OBSERVE SERVICE STATE
    val currentSentenceIndex by readerService?.currentSentenceIndexFlow?.collectAsState(initial = 0) ?: remember { mutableIntStateOf(0) }
    val isPlaying by readerService?.isPlayingFlow?.collectAsState(initial = false) ?: remember { mutableStateOf(false) }

    val textListState = rememberLazyListState()

    // Restore saved page on first bind
    LaunchedEffect(isBound) {
        if (isBound) {
            withContext(Dispatchers.IO) {
                val saved = BookStore.getBook(context, uri.toString())
                withContext(Dispatchers.Main) {
                    if (saved != null) currentPage = saved.currentPage
                }
            }
        }
    }

    // Sync speed to service
    LaunchedEffect(playbackSpeed, readerService) {
        readerService?.setSpeed(playbackSpeed)
    }

    // Listen for page end (continuous reading)
    LaunchedEffect(readerService, totalPages, currentPage) {
        readerService?.onPageEndReached = {
            if (currentPage < totalPages - 1) {
                currentPage++
            } else {
                readerService?.pauseAudio() // End of book
            }
        }
    }

    // Sync page content & save progress
    LaunchedEffect(currentPage, uri, isBound) {
        if (!isBound) return@LaunchedEffect

        // Save progress (now async O(1) via Room — Issue #4)
        val fileName = FileNameUtils.getFileName(context, uri)
        BookStore.saveBookProgress(context, uri, currentPage, totalPages, fileName)

        // Extract text using cached PDDocument (Issue #2)
        withContext(Dispatchers.IO) {
            val extractedSentences = pdfHelper.extractTextFromPage(context, uri, currentPage)
            withContext(Dispatchers.Main) {
                sentences = extractedSentences
                readerService?.setSentences(extractedSentences)
            }
        }
    }

    // Auto-scroll sentence in text mode
    LaunchedEffect(currentSentenceIndex) {
        if (!isVisualMode && sentences.isNotEmpty()) {
            textListState.animateScrollToItem(currentSentenceIndex)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────────

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

            // MAIN CONTENT
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

            // BOTTOM CONTROLS
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