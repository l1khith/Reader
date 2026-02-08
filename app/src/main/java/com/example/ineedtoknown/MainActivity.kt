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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ineedtoknown.ui.theme.IneedtoknownTheme // Make sure this matches your Theme name
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
                // 1. Grant permissions
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                // 2. Get the REAL file name
                val realName = FileNameUtils.getFileName(context, it)

                // 3. Save to BookStore immediately so Library sees it
                // We assume page 0 and 1 total page initially, Reader will update this later
                BookStore.saveBookProgress(context, it, 0, 1, realName)

                // 4. Navigate
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
                    pdfLauncher.launch(arrayOf("application/pdf"))
                }
            )
        }

        // --- SCREEN 2: READER (PLAYER) ---
        composable(
            route = "reader/{uriString}",
            arguments = listOf(navArgument("uriString") { type = NavType.StringType })
        ) { backStackEntry ->
            val uriString = backStackEntry.arguments?.getString("uriString")

            if (uriString != null) {
                val uri = Uri.parse(uriString)

                ReaderScreen(
                    uri = uri,
                    pdfHelper = PdfHelper,
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}