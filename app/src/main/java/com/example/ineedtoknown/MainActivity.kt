package com.example.ineedtoknown


import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ineedtoknown.ui.theme.IneedtoknownTheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize PDFBox (Critical for text extraction)
        PDFBoxResourceLoader.init(applicationContext)

        setContent {
            IneedtoknownTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val context = LocalContext.current

    // Launcher for picking PDFs
    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let {
                // 1. Grant persistent permission so we can read it later/after reboot
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                // 2. Navigate to the reader
                // We MUST encode the URI string because it contains "/" characters
                // that confuse the navigator.
                val encodedUri = Uri.encode(it.toString())
                navController.navigate("reader/$encodedUri")
            }
        }
    )

    NavHost(navController = navController, startDestination = "library") {

        // --- SCREEN 1: LIBRARY (HOME) ---
        composable("library") {
            LibraryScreen(
                onBookClick = { uri ->
                    val encodedUri = Uri.encode(uri.toString())
                    navController.navigate("reader/$encodedUri")
                },
                onAddBookClick = {
                    // Launch system file picker for PDFs
                    pdfLauncher.launch(arrayOf("application/pdf"))
                }
            )
        }

        // --- SCREEN 2: READER (PLAYER) ---
        composable(
            route = "reader/{uriString}",
            arguments = listOf(navArgument("uriString") { type = NavType.StringType })
        ) { backStackEntry ->
            // Extract the URI from the route
            val uriString = backStackEntry.arguments?.getString("uriString")

            if (uriString != null) {
                val uri = uriString.toUri()

                ReaderScreen(
                    uri = uri,
                    pdfHelper = PdfHelper, // Passing your singleton object
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}