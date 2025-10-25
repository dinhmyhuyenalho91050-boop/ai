package com.example.htmlapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.example.htmlapp.ui.HtmlAppScreen
import com.example.htmlapp.ui.HtmlAppViewModel
import com.example.htmlapp.ui.theme.HtmlAppTheme

class MainActivity : ComponentActivity() {
    private val viewModel: HtmlAppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            HtmlAppTheme {
                HtmlAppRoot(viewModel)
            }
        }
    }
}

@Composable
private fun HtmlAppRoot(viewModel: HtmlAppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            viewModel.exportBackup(uri)
        }
    }

    HtmlAppScreen(
        state = state,
        onSelectSession = viewModel::selectSession,
        onSelectModel = viewModel::selectModel,
        onComposerChange = viewModel::onComposerChanged,
        onSendMessage = viewModel::sendMessage,
        onNewChat = viewModel::newChat,
        onOpenSettings = {
            // TODO: implement settings dialog
        },
        onLoadMoreHistory = viewModel::loadMoreMessages,
        onStopStreaming = viewModel::stopStreaming,
        onExportBackup = {
            val filename = "htmlapp-backup-${System.currentTimeMillis()}.json"
            backupLauncher.launch(filename)
        },
    )
}
