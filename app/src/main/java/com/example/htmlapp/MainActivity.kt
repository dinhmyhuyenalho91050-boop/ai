package com.example.htmlapp

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import com.example.htmlapp.ui.HtmlAppScreen
import com.example.htmlapp.ui.theme.HtmlAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            HtmlAppTheme {
                HtmlAppRoot()
            }
        }
    }
}

@Composable
private fun HtmlAppRoot() {
    val viewModel: MainViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: SecurityException) {
                // ignore and continue without persisted permission
            }
            viewModel.onImportBackup(uri)
        }
    }

    HtmlAppScreen(
        state = uiState,
        onSelectSession = viewModel::onSelectSession,
        onSelectModel = viewModel::onSelectModel,
        onComposerChange = viewModel::onComposerChange,
        onSendMessage = viewModel::onSendMessage,
        onStopStreaming = viewModel::onStopStreaming,
        onLoadMore = viewModel::onLoadMore,
        onNewChat = viewModel::onNewChat,
        onOpenSettings = { viewModel.onToggleSettings(true) },
        onDismissSettings = { viewModel.onToggleSettings(false) },
        onExportBackup = viewModel::onExportBackup,
        onImportBackup = { importLauncher.launch(arrayOf("application/json")) },
        onApiKeyChange = viewModel::onApiKeyChange,
        onDismissStatus = viewModel::onDismissStatus,
    )
}
