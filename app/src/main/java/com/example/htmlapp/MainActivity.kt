package com.example.htmlapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
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
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.view.WindowManager
import androidx.recyclerview.widget.RecyclerView

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
            sessionsDialog?.dismiss()
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
    private var sessionsDialog: BottomSheetDialog? = null
    private var sessionsDialogView: View? = null
    private var isUpdatingComposer = false
    private var lastRenderedMessageCount = 0
    private var currentSettingsTab = SettingsTab.MODELS
    private val promptPresetAdapter = PromptPresetAdapter()
    private var latestUiState: HtmlAppUiState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMessageList()
        setupComposer()
        setupActions()
        collectState()
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
            showSessionsDialog(latestUiState ?: viewModel.uiState.value)
        }
        binding.btnSettings.setOnClickListener {
            viewModel.onOpenSettings()
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
        latestUiState = state
        updateSessions(state)
        updateModels(state)
        updateMessages(state)
        updateComposer(state)
        updateControls(state)
        handleToast(state)
        updateSettingsDialog(state)
        updateSessionsDialog(state)
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
        settingsDialogView = dialogView

        dialogView.findViewById<ImageButton>(R.id.btnCloseSettings)?.setOnClickListener {
            settingsDialog?.dismiss()
        }
        dialogView.findViewById<Button>(R.id.btnCancelSettings)?.setOnClickListener {
            settingsDialog?.dismiss()
        }
        dialogView.findViewById<Button>(R.id.btnApplySettings)?.setOnClickListener {
            val current = latestUiState ?: state
            viewModel.onUpdateSettings(
                apiKey = current.apiKey,
                baseUrl = current.baseUrlOverride,
                enableMock = current.enableMockResponses,
            )
        }

        dialogView.findViewById<Button>(R.id.tabModels)?.setOnClickListener {
            selectSettingsTab(SettingsTab.MODELS, latestUiState ?: state)
        }
        dialogView.findViewById<Button>(R.id.tabPrompts)?.setOnClickListener {
            selectSettingsTab(SettingsTab.PROMPTS, latestUiState ?: state)
        }
        dialogView.findViewById<Button>(R.id.tabBackup)?.setOnClickListener {
            selectSettingsTab(SettingsTab.BACKUP, latestUiState ?: state)
        }

        settingsDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setOnDismissListener {
                settingsDialog = null
                settingsDialogView = null
                currentSettingsTab = SettingsTab.MODELS
                viewModel.onDismissSettings()
            }
            .create()

        settingsDialog?.show()
        settingsDialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settingsDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        selectSettingsTab(currentSettingsTab, state)
    }

    private fun updateSettingsDialog(state: HtmlAppUiState) {
        val dialogView = settingsDialogView ?: return
        dialogView.findViewById<Button>(R.id.btnApplySettings)?.isEnabled = !state.isBackupInProgress
        dialogView.findViewById<Button>(R.id.btnExportAll)?.let { button ->
            val baseLabel = (button.tag as? String) ?: button.text.toString().also { button.tag = it }
            button.isEnabled = !state.isBackupInProgress
            button.text = if (state.isBackupInProgress) {
                getString(R.string.settings_exporting)
            } else {
                baseLabel
            }
        }
        dialogView.findViewById<Button>(R.id.btnExportSessions)?.isEnabled = !state.isBackupInProgress
        dialogView.findViewById<Button>(R.id.btnExportPresets)?.isEnabled = !state.isBackupInProgress
        dialogView.findViewById<TextView>(R.id.sessionCount)?.text = state.sessions.size.toString()
        dialogView.findViewById<TextView>(R.id.messageCount)?.text = state.messages.size.toString()
        dialogView.findViewById<TextView>(R.id.modelPresetCount)?.text = state.availableModels.size.toString()
        dialogView.findViewById<TextView>(R.id.promptPresetCount)?.text = "0"
        dialogView.findViewById<TextView>(R.id.dataSize)?.text = "-"
    }

    private fun selectSettingsTab(tab: SettingsTab, state: HtmlAppUiState?) {
        val dialogView = settingsDialogView ?: return
        currentSettingsTab = tab
        dialogView.findViewById<Button>(R.id.tabModels)?.isSelected = tab == SettingsTab.MODELS
        dialogView.findViewById<Button>(R.id.tabPrompts)?.isSelected = tab == SettingsTab.PROMPTS
        dialogView.findViewById<Button>(R.id.tabBackup)?.isSelected = tab == SettingsTab.BACKUP

        val container = dialogView.findViewById<ViewGroup>(R.id.settingsPaneContainer) ?: return
        container.removeAllViews()
        val layoutId = when (tab) {
            SettingsTab.MODELS -> R.layout.pane_models
            SettingsTab.PROMPTS -> R.layout.pane_prompts
            SettingsTab.BACKUP -> R.layout.pane_backup
        }
        LayoutInflater.from(this).inflate(layoutId, container, true)
        when (tab) {
            SettingsTab.MODELS -> bindModelsPane(container, state)
            SettingsTab.PROMPTS -> bindPromptsPane(container)
            SettingsTab.BACKUP -> bindBackupPane(container)
        }
        state?.let { updateSettingsDialog(it) }
    }

    private fun bindModelsPane(container: ViewGroup, state: HtmlAppUiState?) {
        val currentModel = state?.availableModels?.firstOrNull()
        container.findViewById<EditText>(R.id.singleModelName)?.setText(currentModel?.displayName.orEmpty())
        container.findViewById<SwitchCompat>(R.id.modelToggle)?.isChecked = currentModel != null
    }

    private fun bindPromptsPane(container: ViewGroup) {
        val recycler = container.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.promptPresetList)
        recycler?.layoutManager = LinearLayoutManager(this)
        recycler?.adapter = promptPresetAdapter
        promptPresetAdapter.submitList(
            listOf(PromptPresetItem(name = "默认预设", description = "空", isSelected = true))
        )

        val rulesContainer = container.findViewById<LinearLayout>(R.id.regexRulesContainer)
        container.findViewById<Button>(R.id.btnAddRegexRule)?.setOnClickListener {
            val ruleView = LayoutInflater.from(this).inflate(R.layout.item_regex_rule, rulesContainer, false)
            ruleView.findViewById<ImageButton>(R.id.btnRemoveRule)?.setOnClickListener {
                rulesContainer?.removeView(ruleView)
            }
            rulesContainer?.addView(ruleView)
        }

        container.findViewById<Button>(R.id.btnSavePromptPreset)?.setOnClickListener {
            Toast.makeText(this, "保存预设功能即将上线", Toast.LENGTH_SHORT).show()
        }
        container.findViewById<Button>(R.id.btnUpdatePromptPreset)?.setOnClickListener {
            Toast.makeText(this, "更新预设功能即将上线", Toast.LENGTH_SHORT).show()
        }
        container.findViewById<Button>(R.id.btnDeletePromptPreset)?.setOnClickListener {
            Toast.makeText(this, "删除预设功能即将上线", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindBackupPane(container: ViewGroup) {
        val importModes = listOf("合并数据", "覆盖数据")
        val spinner = container.findViewById<Spinner>(R.id.importMode)
        spinner?.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, importModes).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        container.findViewById<Button>(R.id.btnExportAll)?.setOnClickListener {
            viewModel.onExportBackup()
        }
        container.findViewById<Button>(R.id.btnExportSessions)?.setOnClickListener {
            Toast.makeText(this, "敬请期待", Toast.LENGTH_SHORT).show()
        }
        container.findViewById<Button>(R.id.btnExportPresets)?.setOnClickListener {
            Toast.makeText(this, "敬请期待", Toast.LENGTH_SHORT).show()
        }
        container.findViewById<Button>(R.id.btnImport)?.setOnClickListener {
            Toast.makeText(this, "导入功能即将上线", Toast.LENGTH_SHORT).show()
        }
        container.findViewById<Button>(R.id.btnClearAll)?.setOnClickListener {
            Toast.makeText(this, "清空功能即将上线", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSessionsDialog(state: HtmlAppUiState) {
        val existing = sessionsDialog
        if (existing?.isShowing == true) {
            updateSessionsDialog(state)
            return
        }
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sessions, null)
        sessionsDialogView = dialogView

        val recycler = dialogView.findViewById<RecyclerView>(R.id.sessionRecyclerView)
        recycler?.layoutManager = LinearLayoutManager(this)
        recycler?.adapter = sessionAdapter

        dialogView.findViewById<View>(R.id.btnCloseSessions)?.setOnClickListener {
            sessionsDialog?.dismiss()
        }
        dialogView.findViewById<Button>(R.id.btnNewChat)?.setOnClickListener {
            viewModel.onNewChat()
            sessionsDialog?.dismiss()
        }

        val bottomSheet = BottomSheetDialog(this)
        bottomSheet.setContentView(dialogView)
        bottomSheet.setOnShowListener { dialog ->
            (dialog as? BottomSheetDialog)?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundResource(android.R.color.transparent)
        }
        bottomSheet.setOnDismissListener {
            sessionsDialog = null
            sessionsDialogView = null
        }
        sessionsDialog = bottomSheet
        bottomSheet.show()
        updateSessionsDialog(state)
    }

    private fun updateSessionsDialog(state: HtmlAppUiState) {
        val dialogView = sessionsDialogView ?: return
        val isEmpty = state.sessions.isEmpty()
        dialogView.findViewById<TextView>(R.id.emptySessions)?.isVisible = isEmpty
        dialogView.findViewById<RecyclerView>(R.id.sessionRecyclerView)?.isVisible = !isEmpty
        dialogView.findViewById<View>(R.id.btnNewChat)?.isEnabled = !state.isSending
        dialogView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.newChatModelSelector)?.updateModelChips(
            models = state.availableModels,
            selectedModelId = state.selectedModelId,
            onSelect = viewModel::onSelectModel,
        )
    }

    private enum class SettingsTab { MODELS, PROMPTS, BACKUP }

    private class PromptPresetAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<PromptPresetAdapter.ViewHolder>() {
        private var items: List<PromptPresetItem> = emptyList()

        fun submitList(data: List<PromptPresetItem>) {
            items = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_prompt_preset, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.presetName)
            private val desc: TextView = view.findViewById(R.id.presetDesc)

            fun bind(item: PromptPresetItem) {
                name.text = item.name
                desc.text = item.description
                itemView.isSelected = item.isSelected
            }
        }
    }

    private data class PromptPresetItem(
        val name: String,
        val description: String,
        val isSelected: Boolean = false,
    )
}
