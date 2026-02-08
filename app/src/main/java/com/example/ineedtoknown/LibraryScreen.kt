package com.example.ineedtoknown

import android.net.Uri
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.example.ineedtoknown.ui.theme.AccentBlue
import com.example.ineedtoknown.ui.theme.AppBackground
import com.example.ineedtoknown.ui.theme.SurfaceDark
import com.example.ineedtoknown.ui.theme.TextGrey
import com.example.ineedtoknown.ui.theme.TextWhite

@Composable
fun LibraryScreen(
    onBookClick: (Uri) -> Unit,
    onAddBookClick: () -> Unit
) {
    val context = LocalContext.current
    // 1. Load Real Data
    var allBooks by remember { mutableStateOf(BookStore.getAllBooks(context)) }
    var searchQuery by remember { mutableStateOf("") }

    // Refresh data when screen appears (in case you just came back from reading)
    LaunchedEffect(Unit) {
        allBooks = BookStore.getAllBooks(context)
    }

    // 2. Search Logic
    val filteredBooks = remember(allBooks, searchQuery) {
        if (searchQuery.isBlank()) allBooks
        else allBooks.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    // 3. Find "Continue Reading" (Most recent book)
    val recentBook = allBooks.firstOrNull()

    Scaffold(
        containerColor = AppBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddBookClick,
                containerColor = AccentBlue,
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add PDF")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Menu, "Menu", tint = TextWhite)
                Text("Library", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                Box(modifier = Modifier.size(32.dp).background(Color.Gray, CircleShape))
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Real Search Bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search your library...", color = TextGrey) },
                leadingIcon = { Icon(Icons.Default.Search, "Search", tint = TextGrey) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Continue Reading (Only show if we have history)
            if (recentBook != null) {
                Text("Continue Reading", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(12.dp))
                ContinueReadingCard(
                    book = recentBook,
                    onClick = { onBookClick(recentBook.uriString.toUri()) }
                )
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("All Documents", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Icon(Icons.Default.GridView, "Grid", tint = TextGrey)
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (filteredBooks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No books found. Tap + to add one!", color = TextGrey)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredBooks) { book ->
                        BookGridItem(
                            book = book,
                            onClick = { onBookClick(book.uriString.toUri()) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BookGridItem(book: BookData, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
            ) { onClick() }
    ) {
        // Placeholder Cover
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(SurfaceDark, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("PDF", color = TextGrey, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = book.title,
            color = TextWhite,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        // Real Progress Calculation
        val progressPercent = (book.getProgress() * 100).toInt()
        Text(
            text = "${progressPercent}% Read",
            color = AccentBlue,
            fontSize = 12.sp
        )
    }
}

@Composable
fun ContinueReadingCard(book: BookData, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(SurfaceDark, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple()
            ) { onClick() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .background(Color(0xFF334155), RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                book.title,
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { book.getProgress() },
                    modifier = Modifier.width(100.dp).height(4.dp),
                    color = AccentBlue,
                    trackColor = Color.DarkGray,
                )
                Spacer(modifier = Modifier.width(8.dp))
                val percent = (book.getProgress() * 100).toInt()
                Text("$percent%", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}