package com.example.htmlapp

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.htmlapp.databinding.ActivityMainBinding
import com.example.htmlapp.ui.HtmlAppUiState
import com.example.htmlapp.ui.HtmlAppViewModel
import com.example.htmlapp.ui.HtmlAppViewModelFactory
import com.example.htmlapp.ui.adapter.ChatMessageAdapter
import com.example.htmlapp.ui.adapter.SessionListAdapter
import com.example.htmlapp.ui.widget.updateModelChips
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val appContainer: AppContainer by lazy { AppContainer(applicationContext) }
    private val viewModel: HtmlAppViewModel by viewModels {
        HtmlAppViewModelFactory(
            chatRepository = appContainer.chatRepository,
            settingsRepository = appContainer.settingsRepository,
            backupManager = appContainer.backupManager,
            streamingClient = appContainer.streamingClient,
            messageWindowManager = appContainer.messageWindowManager,
            modelPresets = appContainer.modelPresets,
        )
    }

    private lateinit var binding: ActivityMainBinding
    private val sessionAdapter = SessionListAdapter(
        onOpen = { sessionId ->
            viewModel.onSelectSession(sessionId)
            binding.drawerLayout.closeDrawers()
        },
        onDelete = { sessionId -> viewModel.onDeleteSession(sessionId) },
    )

    private var isLoadingMore = false

    private val messageAdapter = ChatMessageAdapter(
        onLoadMore = {
            isLoadingMore = true
            viewModel.onLoadMore()
        },
        onDelete = { messageId -> viewModel.onDeleteMessage(messageId) },
        onStopStreaming = { viewModel.onStopStreaming() },
        onRegenerate = { viewModel.onRegenerateMessage(it) },
    )

    private var settingsDialog: android.app.Dialog? = null
    private var settingsDialogView: View? = null
    private var isUpdatingComposer = false
    private var lastRenderedMessageCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSessionList()
        setupMessageList()
        setupComposer()
        setupActions()
        collectState()
    }

    private fun setupSessionList() {
        binding.sessionRecyclerView.apply {
            adapter = sessionAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
    }

    private fun setupMessageList() {
        binding.messagesRecyclerView.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
        }
    }

    private fun setupComposer() {
        binding.btnSend.setOnClickListener {
            viewModel.onSendMessage()
        }

        binding.inputMessage.addTextChangedListener { text ->
            if (isUpdatingComposer) return@addTextChangedListener
            viewModel.onComposerChange(text?.toString().orEmpty())
        }
    }

    private fun setupActions() {
        binding.btnSessions.setOnClickListener {
            binding.drawerLayout.openDrawer(Gravity.START)
        }
        binding.btnSettings.setOnClickListener {
            viewModel.onOpenSettings()
        }
        binding.btnNewChat.setOnClickListener {
            binding.drawerLayout.closeDrawers()
            viewModel.onNewChat()
        }

    }

    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: HtmlAppUiState) {
        updateSessions(state)
        updateModels(state)
        updateMessages(state)
        updateComposer(state)
        updateControls(state)
        handleToast(state)
        updateSettingsDialog(state)
        if (state.isSettingsVisible) {
            showSettingsDialog(state)
        } else {
            settingsDialog?.dismiss()
        }
    }

    private fun updateSessions(state: HtmlAppUiState) {
        sessionAdapter.submitList(state.sessions)
    }

    private fun updateModels(state: HtmlAppUiState) {
        binding.modelSelector.updateModelChips(
            models = state.availableModels,
            selectedModelId = state.selectedModelId,
            onSelect = viewModel::onSelectModel,
        )
        binding.newChatModelSelector.updateModelChips(
            models = state.availableModels,
            selectedModelId = state.selectedModelId,
            onSelect = viewModel::onSelectModel,
        )
    }

    private fun updateMessages(state: HtmlAppUiState) {
        val items = messageAdapter.buildItems(state.messages, state.canLoadMore)
        val shouldScroll = !isLoadingMore && state.messages.size >= lastRenderedMessageCount
        messageAdapter.submitList(items) {
            if (shouldScroll && messageAdapter.itemCount > 0) {
                binding.messagesRecyclerView.scrollToPosition(messageAdapter.itemCount - 1)
            }
        }
        lastRenderedMessageCount = state.messages.size
        isLoadingMore = false
        binding.emptyState.isVisible = state.messages.isEmpty()
    }

    private fun updateComposer(state: HtmlAppUiState) {
        val current = binding.inputMessage.text?.toString().orEmpty()
        if (current != state.composerText) {
            isUpdatingComposer = true
            binding.inputMessage.setText(state.composerText)
            binding.inputMessage.setSelection(binding.inputMessage.text?.length ?: 0)
            isUpdatingComposer = false
        }
    }

    private fun updateControls(state: HtmlAppUiState) {
        binding.btnSend.isEnabled = state.composerText.isNotBlank() && !state.isSending
        binding.btnSend.text = if (state.isSending) {
            getString(R.string.send_in_progress)
        } else {
            getString(R.string.action_send)
        }
        binding.progressSending.isVisible = state.isSending
    }

    private fun handleToast(state: HtmlAppUiState) {
        val message = state.toastMessage ?: return
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        viewModel.onDismissToast()
    }

    private fun showSettingsDialog(state: HtmlAppUiState) {
        if (settingsDialog?.isShowing == true) return
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val apiKeyInput = dialogView.findViewById<EditText>(R.id.inputApiKey)
        val baseUrlInput = dialogView.findViewById<EditText>(R.id.inputBaseUrl)
        val mockSwitch = dialogView.findViewById<Switch>(R.id.switchMockResponses)
        val exportButton = dialogView.findViewById<Button>(R.id.btnExportBackup)
        exportButton.setOnClickListener {
            viewModel.onExportBackup()
        }

        apiKeyInput.setText(state.apiKey)
        baseUrlInput.setText(state.baseUrlOverride.orEmpty())
        mockSwitch.isChecked = state.enableMockResponses
        exportButton.isEnabled = !state.isBackupInProgress
        exportButton.text = if (state.isBackupInProgress) {
            getString(R.string.settings_exporting)
        } else {
            getString(R.string.settings_export)
        }

        settingsDialogView = dialogView

        settingsDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_save) { _, _ ->
                viewModel.onUpdateSettings(
                    apiKey = apiKeyInput.text?.toString().orEmpty(),
                    baseUrl = baseUrlInput.text?.toString()?.takeIf { it.isNotBlank() },
                    enableMock = mockSwitch.isChecked,
                )
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                dialog.dismiss()
                viewModel.onDismissSettings()
            }
            .setOnDismissListener {
                settingsDialog = null
                settingsDialogView = null
                viewModel.onDismissSettings()
            }
            .create()

        settingsDialog?.show()
    }

    private fun updateSettingsDialog(state: HtmlAppUiState) {
        val dialogView = settingsDialogView ?: return
        val apiKeyInput = dialogView.findViewById<EditText>(R.id.inputApiKey)
        val baseUrlInput = dialogView.findViewById<EditText>(R.id.inputBaseUrl)
        val mockSwitch = dialogView.findViewById<Switch>(R.id.switchMockResponses)
        val exportButton = dialogView.findViewById<Button>(R.id.btnExportBackup) ?: return
        if (apiKeyInput.text?.toString() != state.apiKey) {
            apiKeyInput.setText(state.apiKey)
        }
        val baseUrl = state.baseUrlOverride.orEmpty()
        if (baseUrlInput.text?.toString() != baseUrl) {
            baseUrlInput.setText(baseUrl)
        }
        if (mockSwitch.isChecked != state.enableMockResponses) {
            mockSwitch.isChecked = state.enableMockResponses
        }
        exportButton.isEnabled = !state.isBackupInProgress
        exportButton.text = if (state.isBackupInProgress) {
            getString(R.string.settings_exporting)
        } else {
            getString(R.string.settings_export)
        }
    }
}
