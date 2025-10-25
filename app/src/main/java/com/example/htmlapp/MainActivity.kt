package com.example.htmlapp

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.htmlapp.ui.ChatViewModel
import com.example.htmlapp.ui.HtmlAppScreen
import com.example.htmlapp.ui.theme.HtmlAppTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val exportLauncher = registerForActivityResult(CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = viewModel.exportBackup(uri)
            Toast.makeText(
                this@MainActivity,
                if (result.isSuccess) "备份已导出" else "导出失败：${result.exceptionOrNull()?.message}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val importLauncher = registerForActivityResult(OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = viewModel.importBackup(uri)
            Toast.makeText(
                this@MainActivity,
                if (result.isSuccess) "备份导入成功" else "导入失败：${result.exceptionOrNull()?.message}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            HtmlAppTheme {
                val state = viewModel.uiState.collectAsStateWithLifecycle().value

                HtmlAppScreen(
                    state = state,
                    onSelectSession = viewModel::onSelectSession,
                    onSelectModel = viewModel::onSelectModel,
                    onComposerChange = viewModel::onComposerChange,
                    onSendMessage = viewModel::onSendMessage,
                    onNewChat = viewModel::onNewChat,
                    onOpenSettings = viewModel::openSettings,
                    onLoadOlder = viewModel::onLoadOlder,
                    onStopStreaming = viewModel::onStopStreaming,
                    onDismissError = viewModel::onDismissError,
                    onCloseSettings = viewModel::closeSettings,
                    onSaveSettings = viewModel::saveSettings,
                    onRequestBackupExport = { launchExport() },
                    onRequestBackupImport = { launchImport() },
                )
            }
        }
    }

    private fun launchExport() {
        val fileName = "ai-chat-backup-${System.currentTimeMillis()}.json"
        exportLauncher.launch(fileName)
    }

    private fun launchImport() {
        importLauncher.launch(arrayOf("application/json", "text/plain"))
    }
}

