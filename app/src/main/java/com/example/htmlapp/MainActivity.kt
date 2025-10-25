package com.example.htmlapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import com.example.htmlapp.ui.HtmlAppScreen
import com.example.htmlapp.ui.HtmlAppViewModel
import com.example.htmlapp.ui.HtmlAppViewModelFactory
import com.example.htmlapp.ui.theme.HtmlAppTheme

class MainActivity : ComponentActivity() {
    private val appContainer: AppContainer by lazy { AppContainer(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            HtmlAppTheme {
                HtmlAppRoot(appContainer)
            }
        }
    }
}

@Composable
private fun HtmlAppRoot(appContainer: AppContainer) {
    val factory = rememberHtmlAppFactory(appContainer)
    val viewModel: HtmlAppViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    HtmlAppScreen(
        state = state,
        onSelectSession = viewModel::onSelectSession,
        onSelectModel = viewModel::onSelectModel,
        onComposerChange = viewModel::onComposerChange,
        onSendMessage = viewModel::onSendMessage,
        onNewChat = viewModel::onNewChat,
        onOpenSettings = viewModel::onOpenSettings,
        onLoadMore = viewModel::onLoadMore,
        onStopStreaming = viewModel::onStopStreaming,
        onExportBackup = viewModel::onExportBackup,
        onDismissToast = viewModel::onDismissToast,
        onDismissSettings = viewModel::onDismissSettings,
        onUpdateSettings = viewModel::onUpdateSettings,
    )
}

@Composable
private fun rememberHtmlAppFactory(appContainer: AppContainer): HtmlAppViewModelFactory {
    return remember(appContainer) {
        HtmlAppViewModelFactory(
            chatRepository = appContainer.chatRepository,
            settingsRepository = appContainer.settingsRepository,
            backupManager = appContainer.backupManager,
            streamingClient = appContainer.streamingClient,
            messageWindowManager = appContainer.messageWindowManager,
            modelPresets = appContainer.modelPresets,
        )
    }
}
