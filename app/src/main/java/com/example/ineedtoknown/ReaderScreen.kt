package com.example.ineedtoknown

import android.graphics.Bitmap
import android.net.Uri
import android.widget.ToggleButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ineedtoknown.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    uri: Uri,
    pdfHelper: PdfHelper,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var currentPage by remember { mutableIntStateOf(0) }
    var pageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var extractedText by remember { mutableStateOf("") }
    var isVisualMode by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }

    // Load PDF Page Data
    LaunchedEffect(currentPage, uri) {
        // Run heavy PDF tasks on IO thread
        withContext(Dispatchers.IO) {
            // FIX 1: Use 'renderPageToBitmap' (correct name from your PdfHelper)
            val bitmap = pdfHelper.renderPageToBitmap(context, uri, currentPage)

            // FIX 2: Use 'extractTextFromPage' and join the list into a single string
            val sentences = pdfHelper.extractTextFromPage(context, uri, currentPage)
            val fullText = sentences.joinToString(" ")

            withContext(Dispatchers.Main) {
                pageBitmap = bitmap
                extractedText = fullText
            }
        }
    }

    Scaffold(
        containerColor = AppBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Document Viewer", color = TextWhite, fontSize = 16.sp)
                        Text("Page ${currentPage + 1}", color = TextGrey, fontSize = 12.sp)
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

            // --- MAIN CONTENT AREA ---
            if (isVisualMode) {
                if (pageBitmap != null) {
                    Image(
                        bitmap = pageBitmap!!.asImageBitmap(),
                        contentDescription = "PDF Page",
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.85f)
                            .padding(16.dp)
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = AccentBlue)
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.85f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(text = extractedText, color = TextWhite, fontSize = 16.sp, lineHeight = 24.sp)
                }
            }

            // --- BOTTOM PLAYER BAR ---
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(SurfaceDark, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(24.dp)
            ) {
                // Progress & Controls (Same as before)
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Page ${currentPage + 1}", color = TextGrey, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Visual/Text Toggle
                    Row(
                        modifier = Modifier
                            .background(Color(0xFF2D2D3A), RoundedCornerShape(8.dp))
                            .padding(4.dp)
                    ) {
                        ToggleButton(text = "Visual", isSelected = isVisualMode) {
                            isVisualMode = true
                        }
                        ToggleButton(text = "Text", isSelected = !isVisualMode) { isVisualMode = false }
                    }

                    // Navigation Controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (currentPage > 0) currentPage-- }) {
                            Icon(Icons.Default.Replay10, "Prev Page", tint = TextGrey) // Using Replay icon for Prev
                        }

                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .background(AccentBlue, CircleShape)
                                .clickable { isPlaying = !isPlaying },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                "Play",
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        IconButton(onClick = { currentPage++ }) {
                            Icon(Icons.Default.Forward10, "Next Page", tint = TextGrey) // Using Forward icon for Next
                        }
                    }
                    Text("1.0x", color = TextWhite, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}