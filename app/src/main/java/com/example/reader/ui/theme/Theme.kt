package com.example.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// App always uses its own dark palette — no dynamic/light mode branching needed.
private val AppColorScheme = darkColorScheme()

@Composable
fun ReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        content = content
    )
}