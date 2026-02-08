package com.example.ineedtoknown

import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            // 1. Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Menu, "Menu", tint = TextWhite)
                Text(
                    text = "Library",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextWhite
                )
                // Placeholder for profile icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.Gray, CircleShape)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 2. Search Bar
            SearchBarMock()

            Spacer(modifier = Modifier.height(24.dp))

            // 3. Continue Reading Section
            Text(
                "Continue Reading",
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            ContinueReadingCard() // Custom Composable for the top card

            Spacer(modifier = Modifier.height(24.dp))

            // 4. All Documents Grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("All Documents", color = TextWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Icon(Icons.Default.GridView, "Grid", tint = TextGrey)
            }
            Spacer(modifier = Modifier.height(12.dp))

            // Grid Implementation
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(4) { index -> // Mocking 4 items
                    BookGridItem(title = "Document $index")
                }
            }
        }
    }
}

// ... existing LibraryScreen code ...

@Composable
fun BookGridItem(title: String) {
    Column(
        modifier = Modifier
            .width(160.dp)
            .clickable { }
    ) {
        // Book Cover Placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(SurfaceDark, RoundedCornerShape(8.dp)), // Dark grey placeholder
            contentAlignment = Alignment.Center
        ) {
            Text(text = "PDF", color = TextGrey, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Title
        Text(
            text = title,
            color = TextWhite,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            maxLines = 2
        )

        // Metadata
        Text(
            text = "PDF • 3.2 MB",
            color = TextGrey,
            fontSize = 12.sp
        )
    }
}

@Composable
fun ContinueReadingCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .background(SurfaceDark, RoundedCornerShape(16.dp))
            .clickable { }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Mini Cover
        Box(
            modifier = Modifier
                .width(80.dp)
                .fillMaxHeight()
                .background(Color(0xFF334155), RoundedCornerShape(8.dp))
        )

        Spacer(modifier = Modifier.width(16.dp))

        // Info
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "The Design of Everyday Things",
                color = TextWhite,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                "Don Norman",
                color = TextGrey,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Mini Progress Bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { 0.45f },
                    modifier = Modifier.width(100.dp).height(4.dp),
                    color = AccentBlue,
                    trackColor = Color.DarkGray,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("45%", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SearchBarMock() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, "Search", tint = TextGrey)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Search books, authors, or PDFs...", color = TextGrey)
    }
}