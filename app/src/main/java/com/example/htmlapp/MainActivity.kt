package com.example.htmlapp

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var messagesContainer: LinearLayout
    private lateinit var inputMessage: EditText
    private lateinit var sendButton: TextView
    private lateinit var backdrop: View
    private lateinit var sidebar: LinearLayout
    private lateinit var sessionList: LinearLayout
    private lateinit var modelTabs: List<TextView>
    private lateinit var sidebarModelTabs: List<TextView>
    private lateinit var repository: NativeChatRepository
    private lateinit var state: NativeChatState
    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>

    private val aiClient = NativeAiClient()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val messageBodyViews = mutableMapOf<String, TextView>()
    private val messageThinkingViews = mutableMapOf<String, TextView>()
    private var isSending = false
    private var pendingImportReplace = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        setContentView(R.layout.activity_main)

        repository = NativeChatRepository(applicationContext)
        state = repository.load()
        registerImportLauncher()
        bindViews()
        setupEdgeToEdge()
        setupTitleGradient()
        setupClicks()
        setupBackNavigation()
        renderAll()
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        aiClient.abort()
        super.onDestroy()
    }

    private fun registerImportLauncher() {
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalArgumentException("无法读取备份文件")
            }.onSuccess { text ->
                runCatching {
                    repository.importBackup(state, JSONObject(text), pendingImportReplace)
                    state.ensureSession()
                    renderAll()
                    toast("导入成功")
                }.onFailure { toast("导入失败: ${it.message}") }
            }.onFailure {
                toast("读取失败: ${it.message}")
            }
        }
    }

    private fun bindViews() {
        root = findViewById(R.id.root_container)
        messagesScroll = findViewById(R.id.messages_scroll)
        messagesContainer = findViewById(R.id.messages_container)
        inputMessage = findViewById(R.id.input_message)
        sendButton = findViewById(R.id.btn_send)
        backdrop = findViewById(R.id.backdrop)
        sidebar = findViewById(R.id.sidebar)
        sessionList = findViewById(R.id.session_list)
        modelTabs = listOf(findViewById(R.id.model_gpt), findViewById(R.id.model_deepseek))
        sidebarModelTabs = listOf(
            findViewById(R.id.sidebar_model_gpt),
            findViewById(R.id.sidebar_model_deepseek)
        )
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                max(systemBars.bottom, ime.bottom)
            )
            insets
        }
    }

    private fun setupTitleGradient() {
        val title = findViewById<TextView>(R.id.title_text)
        title.doOnLayout {
            title.paint.shader = LinearGradient(
                0f,
                0f,
                title.width.toFloat(),
                title.height.toFloat(),
                intArrayOf(color(R.color.chat_accent_blue), color(R.color.chat_accent)),
                null,
                Shader.TileMode.CLAMP
            )
            title.invalidate()
        }
    }

    private fun setupClicks() {
        findViewById<TextView>(R.id.btn_sessions).setOnClickListener { setSidebarVisible(true) }
        findViewById<TextView>(R.id.btn_settings).setOnClickListener { showSettingsDialog() }
        findViewById<TextView>(R.id.btn_new_chat).setOnClickListener { newChat() }
        backdrop.setOnClickListener { setSidebarVisible(false) }
        sendButton.setOnClickListener {
            if (isSending) stopSending() else submitMessage()
        }

        (modelTabs + sidebarModelTabs).forEachIndexed { absoluteIndex, tab ->
            tab.setOnClickListener {
                val index = absoluteIndex % modelTabs.size
                val enabled = state.enabledPresets()
                if (index < enabled.size) {
                    state.currentModelIndex = index
                    repository.save(state)
                    renderModelTabs()
                    renderSessions()
                }
            }
        }

        inputMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitMessage()
                true
            } else {
                false
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            if (sidebar.visibility == View.VISIBLE) {
                setSidebarVisible(false)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun renderAll() {
        state.ensureSession()
        renderModelTabs()
        renderMessages(scrollToBottom = true)
        renderSessions()
        setSendingUi(isSending)
    }

    private fun setSidebarVisible(visible: Boolean) {
        backdrop.visibility = if (visible) View.VISIBLE else View.GONE
        sidebar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun renderModelTabs() {
        val enabled = state.enabledPresets().take(2)
        state.currentModelIndex = state.currentModelIndex.coerceIn(0, max(0, enabled.size - 1))
        listOf(modelTabs, sidebarModelTabs).forEach { tabs ->
            tabs.forEachIndexed { index, tab ->
                val preset = enabled.getOrNull(index)
                tab.visibility = if (preset == null) View.INVISIBLE else View.VISIBLE
                tab.text = preset?.name ?: ""
                val selected = index == state.currentModelIndex
                tab.setTextColor(if (selected) Color.WHITE else color(R.color.chat_muted))
                tab.background = if (selected) rounded(color(R.color.chat_accent_blue), dp(6)) else null
            }
        }
    }

    private fun submitMessage() {
        if (isSending) return
        val text = inputMessage.text.toString().trim()
        if (text.isEmpty()) return
        val session = state.ensureSession()
        val preset = state.currentPreset()
        session.modelName = preset?.name ?: session.modelName
        session.history.add(ChatMessage(role = "user", content = text, modelName = preset?.name.orEmpty()))
        inputMessage.setText("")
        repository.save(state)
        renderMessages(scrollToBottom = true)
        renderSessions()
        requestAssistant()
    }

    private fun requestAssistant() {
        val session = state.activeSession() ?: return
        val preset = state.currentPreset()
        val assistant = ChatMessage(role = "assistant", content = "", modelName = preset?.name.orEmpty())
        session.history.add(assistant)
        repository.save(state)
        renderMessages(scrollToBottom = true)
        setSendingUi(true)

        Thread {
            runCatching {
                aiClient.send(state, session) { content, thinking ->
                    mainHandler.post {
                        assistant.content = content
                        assistant.thinking = thinking
                        updateMessageViews(assistant)
                    }
                }
            }.onSuccess { result ->
                mainHandler.post {
                    assistant.content = result.content
                    assistant.thinking = result.thinking
                    repository.save(state)
                    updateMessageViews(assistant)
                    renderSessions()
                    setSendingUi(false)
                }
            }.onFailure { error ->
                mainHandler.post {
                    assistant.content = if (error.message.isNullOrBlank()) "请求失败" else "请求失败: ${error.message}"
                    assistant.thinking = ""
                    repository.save(state)
                    updateMessageViews(assistant)
                    renderSessions()
                    setSendingUi(false)
                }
            }
        }.start()
    }

    private fun stopSending() {
        aiClient.abort()
        setSendingUi(false)
    }

    private fun setSendingUi(sending: Boolean) {
        isSending = sending
        sendButton.text = if (sending) "停止" else getString(R.string.action_send)
        sendButton.background = rounded(
            if (sending) color(R.color.chat_danger) else color(R.color.chat_accent_blue),
            dp(8)
        )
    }

    private fun newChat() {
        if (isSending) return
        val preset = state.currentPreset()
        val prompt = state.promptPresets["default"] ?: PromptPreset()
        val session = ChatSession(
            name = "对话 ${state.sessions.size + 1}",
            modelName = preset?.name ?: "未知",
            promptPresetName = "default"
        )
        if (prompt.firstAssistant.isNotBlank()) {
            session.history.add(
                ChatMessage(
                    role = "assistant",
                    content = prompt.firstAssistant,
                    modelName = preset?.name.orEmpty()
                )
            )
        }
        state.sessions[session.id] = session
        state.currentId = session.id
        repository.save(state)
        inputMessage.setText("")
        renderAll()
        setSidebarVisible(false)
    }

    private fun renderMessages(scrollToBottom: Boolean = false) {
        messageBodyViews.clear()
        messageThinkingViews.clear()
        messagesContainer.removeAllViews()
        val session = state.ensureSession()
        session.history.forEachIndexed { index, message ->
            messagesContainer.addView(messageCard(message, index + 1))
        }
        if (scrollToBottom) {
            messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun messageCard(message: ChatMessage, index: Int): View {
        val isAssistant = message.role == "assistant"
        val accent = if (isAssistant) Color.rgb(100, 210, 255) else color(R.color.chat_accent_blue)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.BLACK, dp(14), dp(1), Color.argb(28, 255, 255, 255))
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        card.addView(View(this).apply {
            setBackgroundColor(accent)
            layoutParams = LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT)
        })

        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        contentColumn.addView(messageHeader(message, index))
        contentColumn.addView(separator())

        val thinkingView = TextView(this).apply {
            text = message.thinking
            visibility = if (message.thinking.isBlank()) View.GONE else View.VISIBLE
            setTextColor(color(R.color.chat_accent))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(14), dp(16), 0)
        }
        messageThinkingViews[message.id] = thinkingView
        contentColumn.addView(thinkingView)

        val body = TextView(this).apply {
            text = displayContent(message)
            setTextColor(Color.WHITE)
            textSize = 16f
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(16), dp(18), dp(16), dp(18))
        }
        messageBodyViews[message.id] = body
        contentColumn.addView(body)
        contentColumn.addView(separator())
        contentColumn.addView(messageFooter(message))
        card.addView(contentColumn)
        return card
    }

    private fun displayContent(message: ChatMessage): String {
        return if (message.role == "assistant" && message.content.isBlank() && isSending) "正在思考..." else message.content
    }

    private fun updateMessageViews(message: ChatMessage) {
        messageBodyViews[message.id]?.text = displayContent(message)
        messageThinkingViews[message.id]?.apply {
            text = message.thinking
            visibility = if (message.thinking.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun messageHeader(message: ChatMessage, index: Int): View {
        val roleLabel = if (message.role == "assistant") "ASSISTANT" else "USER"
        val modelLabel = message.modelName.ifBlank { state.currentPreset()?.name.orEmpty() }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            background = solid(Color.argb(8, 255, 255, 255))
            addView(TextView(context).apply {
                text = roleLabel
                setTextColor(Color.WHITE)
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = modelLabel.uppercase(Locale.ROOT)
                setTextColor(color(R.color.chat_accent_blue))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                gravity = Gravity.CENTER
                background = rounded(Color.argb(48, 96, 165, 250), dp(999), dp(1), color(R.color.chat_accent_blue))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            })
            addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(TextView(context).apply {
                text = "#$index"
                setTextColor(color(R.color.chat_muted))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                gravity = Gravity.CENTER
                background = rounded(color(R.color.chat_panel), dp(999), dp(1), color(R.color.chat_border))
                setPadding(dp(9), dp(6), dp(9), dp(6))
            })
        }
    }

    private fun messageFooter(message: ChatMessage): View {
        return LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = solid(Color.argb(7, 255, 255, 255))
            addView(tinyButton("编辑").apply { setOnClickListener { editMessage(message) } })
            if (message.role == "assistant") {
                addView(tinyButton("重新生成").apply { setOnClickListener { regenerateAssistant(message) } })
            }
            addView(tinyButton("删除", danger = true).apply { setOnClickListener { deleteMessage(message) } })
        }
    }

    private fun editMessage(message: ChatMessage) {
        val edit = EditText(this).apply {
            setText(message.content)
            minLines = 6
            setTextColor(Color.WHITE)
            setHintTextColor(color(R.color.chat_muted))
            background = rounded(Color.argb(72, 0, 0, 0), dp(8), dp(1), color(R.color.chat_border))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle("编辑消息")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                message.content = edit.text.toString()
                repository.save(state)
                renderMessages()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteMessage(message: ChatMessage) {
        state.activeSession()?.history?.removeAll { it.id == message.id }
        repository.save(state)
        renderMessages()
        renderSessions()
    }

    private fun regenerateAssistant(message: ChatMessage) {
        if (isSending) return
        val session = state.activeSession() ?: return
        val index = session.history.indexOfFirst { it.id == message.id }
        if (index < 0) return
        session.history.removeAt(index)
        repository.save(state)
        renderMessages(scrollToBottom = true)
        requestAssistant()
    }

    private fun renderSessions() {
        sessionList.removeAllViews()
        state.sessions.values.forEach { session ->
            sessionList.addView(sessionCard(session))
        }
    }

    private fun sessionCard(session: ChatSession): View {
        val active = session.id == state.currentId
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(
                Color.argb(154, 21, 25, 33),
                dp(12),
                dp(1),
                if (active) color(R.color.chat_accent_blue) else color(R.color.chat_border)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            setOnClickListener {
                if (!isSending) {
                    state.currentId = session.id
                    repository.save(state)
                    renderAll()
                    setSidebarVisible(false)
                }
            }

            addView(TextView(context).apply {
                text = session.name
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = "${session.modelName.ifBlank { state.currentPreset()?.name.orEmpty() }} · ${session.history.size} 条消息"
                setTextColor(color(R.color.chat_muted))
                textSize = 11f
                includeFontPadding = false
                setPadding(0, dp(9), 0, 0)
            })
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                setPadding(0, dp(10), 0, 0)
                addView(tinyButton("删除", danger = true).apply {
                    setOnClickListener {
                        if (state.sessions.size <= 1) {
                            toast("至少保留一个对话")
                        } else {
                            state.sessions.remove(session.id)
                            if (state.currentId == session.id) state.currentId = state.sessions.keys.firstOrNull()
                            repository.save(state)
                            renderAll()
                        }
                    }
                })
            })
        }
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val tabs = listOf("模型预设", "提示词", "备份")
        val tabViews = mutableListOf<TextView>()
        var saveCurrentPane: (() -> Unit)? = null

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(color(R.color.chat_panel), dp(16), dp(1), color(R.color.chat_border))
        }
        val tabColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = solid(Color.argb(36, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.MATCH_PARENT)
        }
        tabs.forEachIndexed { index, label ->
            val tab = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(if (index == 0) color(R.color.chat_accent_blue) else color(R.color.chat_muted))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(dp(16), 0, dp(10), 0)
                background = if (index == 0) solid(Color.argb(38, 96, 165, 250)) else null
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            }
            tabViews.add(tab)
            tabColumn.addView(tab)
        }

        val paneScroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, dp(520), 1f)
        }
        paneScroll.addView(contentHost)
        body.addView(tabColumn)
        body.addView(paneScroll)

        val footer = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = solid(Color.argb(24, 0, 0, 0))
        }
        footer.addView(dialogButton("保存").apply {
            setOnClickListener {
                saveCurrentPane?.invoke()
                repository.save(state)
                renderAll()
                toast("已保存")
            }
        })
        footer.addView(dialogButton("关闭").apply { setOnClickListener { dialog.dismiss() } })

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color(R.color.chat_panel), dp(16), dp(1), color(R.color.chat_border))
            addView(body)
            addView(footer)
        }

        fun selectTab(index: Int) {
            tabViews.forEachIndexed { i, tab ->
                tab.setTextColor(if (i == index) color(R.color.chat_accent_blue) else color(R.color.chat_muted))
                tab.background = if (i == index) solid(Color.argb(38, 96, 165, 250)) else null
            }
            contentHost.removeAllViews()
            saveCurrentPane = when (index) {
                0 -> fillModelsPane(contentHost)
                1 -> fillPromptsPane(contentHost)
                else -> fillBackupPane(contentHost, dialog)
            }
        }
        tabViews.forEachIndexed { index, tab -> tab.setOnClickListener { selectTab(index) } }
        selectTab(0)

        dialog.setContentView(shell)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            val width = min(resources.displayMetrics.widthPixels - dp(24), dp(960))
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }

    private fun fillModelsPane(host: LinearLayout): () -> Unit {
        host.addView(dialogTitle("模型预设配置"))
        val editors = mutableListOf<ModelPresetEditor>()
        state.modelPresets.forEachIndexed { index, preset ->
            val editor = modelPresetEditor(index, preset)
            editors.add(editor)
            host.addView(modelPresetCard(editor))
        }
        host.addView(dialogButton("+ 新增预设").apply {
            setOnClickListener {
                state.modelPresets.add(
                    ModelPreset(
                        enabled = false,
                        name = "新预设 ${state.modelPresets.size + 1}",
                        type = "openai",
                        config = ModelConfig()
                    )
                )
                repository.save(state)
                toast("已新增，关闭后重新打开设置可编辑")
            }
        })
        return {
            editors.forEach { it.applyTo(state.modelPresets[it.index]) }
            if (state.enabledPresets().isEmpty()) state.modelPresets.firstOrNull()?.enabled = true
            state.currentModelIndex = state.currentModelIndex.coerceIn(0, max(0, state.enabledPresets().size - 1))
        }
    }

    private fun fillPromptsPane(host: LinearLayout): () -> Unit {
        val preset = state.promptPresets["default"] ?: PromptPreset().also { state.promptPresets["default"] = it }
        host.addView(dialogTitle("默认预设"))
        val name = field("名称", preset.name)
        val sys = field("系统提示词", preset.sysPrompt, multiLine = true)
        val firstUser = field("首条用户消息", preset.firstUser, multiLine = true)
        val firstAssistant = field("首条助手消息", preset.firstAssistant, multiLine = true)
        val prefix = field("消息前缀", preset.messagePrefix, multiLine = true)
        val prefill = field("助手预填", preset.assistantPrefill, multiLine = true)
        listOf(name, sys, firstUser, firstAssistant, prefix, prefill).forEach { host.addView(it.container) }
        return {
            preset.name = name.value()
            preset.sysPrompt = sys.value()
            preset.firstUser = firstUser.value()
            preset.firstAssistant = firstAssistant.value()
            preset.messagePrefix = prefix.value()
            preset.assistantPrefill = prefill.value()
        }
    }

    private fun fillBackupPane(host: LinearLayout, dialog: Dialog): () -> Unit {
        host.addView(dialogTitle("备份"))
        host.addView(dialogButton("导出全部").apply { setOnClickListener { exportBackup("all") } })
        host.addView(dialogButton("导出对话").apply { setOnClickListener { exportBackup("sessions") } })
        host.addView(dialogButton("导出预设").apply { setOnClickListener { exportBackup("presets") } })
        host.addView(separator(dp(16)))
        host.addView(dialogButton("合并导入").apply {
            setOnClickListener {
                pendingImportReplace = false
                dialog.dismiss()
                importLauncher.launch(arrayOf("application/json", "text/*"))
            }
        })
        host.addView(dialogButton("替换导入").apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("替换导入")
                    .setMessage("替换模式会清空当前对话和预设，确定继续？")
                    .setPositiveButton("继续") { _, _ ->
                        pendingImportReplace = true
                        dialog.dismiss()
                        importLauncher.launch(arrayOf("application/json", "text/*"))
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        host.addView(dialogButton("清空所有数据").apply {
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("清空数据")
                    .setMessage("此操作无法恢复，确定删除所有对话、预设和配置吗？")
                    .setPositiveButton("清空") { _, _ ->
                        state = NativeChatState.defaults()
                        state.ensureSession()
                        repository.save(state)
                        dialog.dismiss()
                        renderAll()
                        toast("已清空")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        })
        return {}
    }

    private fun exportBackup(kind: String) {
        runCatching {
            val json = when (kind) {
                "sessions" -> state.toBackupJson(includeSessions = true, includePresets = false)
                "presets" -> state.toBackupJson(includeSessions = false, includePresets = true)
                else -> state.toBackupJson(includeSessions = true, includePresets = true)
            }
            val path = repository.exportBackup(backupFilename(kind), json.toString(2))
            toast("已保存到: $path")
        }.onFailure { toast("导出失败: ${it.message}") }
    }

    private fun modelPresetCard(editor: ModelPresetEditor): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(178, 15, 18, 25), dp(12), dp(1), color(R.color.chat_border))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            addView(editor.enabledRow)
            addView(editor.name.container)
            addView(editor.type.container)
            addView(editor.base.container)
            addView(editor.model.container)
            addView(editor.key.container)
            addView(editor.temperature.container)
            addView(editor.topP.container)
            addView(editor.maxTokens.container)
            addView(editor.streamRow)
            addView(editor.thinkingRow)
            addView(editor.thinkingEffort.container)
        }
    }

    private fun tinyButton(label: String, danger: Boolean = false): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = rounded(
                if (danger) color(R.color.chat_danger) else color(R.color.chat_panel),
                dp(6),
                if (danger) 0 else dp(1),
                color(R.color.chat_border)
            )
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
    }

    private fun dialogTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, 0, 0, dp(18))
        }
    }

    private fun dialogButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = rounded(color(R.color.chat_panel), dp(8), dp(1), color(R.color.chat_border))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(10)
                bottomMargin = dp(10)
            }
        }
    }

    private fun field(label: String, value: String, multiLine: Boolean = false): FieldRef {
        val input = EditText(this).apply {
            setText(value)
            setTextColor(Color.WHITE)
            setHintTextColor(color(R.color.chat_muted))
            textSize = 14f
            minLines = if (multiLine) 3 else 1
            maxLines = if (multiLine) 8 else 1
            setSingleLine(!multiLine)
            gravity = if (multiLine) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(72, 0, 0, 0), dp(8), dp(1), color(R.color.chat_border))
            setPadding(dp(12), if (multiLine) dp(10) else 0, dp(12), if (multiLine) dp(10) else 0)
            layoutParams = LinearLayout.LayoutParams(0, if (multiLine) dp(96) else dp(44), 1f)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(11))
            addView(TextView(context).apply {
                text = label
                setTextColor(color(R.color.chat_muted))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(input)
        }
        return FieldRef(container, input)
    }

    private fun checkboxRow(label: String, checked: Boolean): Pair<LinearLayout, CheckBox> {
        val box = CheckBox(this).apply {
            isChecked = checked
            setTextColor(Color.WHITE)
            text = label
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(box)
        }
        return row to box
    }

    private fun modelPresetEditor(index: Int, preset: ModelPreset): ModelPresetEditor {
        val enabledPair = checkboxRow("启用 ${preset.name}", preset.enabled)
        val streamPair = checkboxRow("流式输出", preset.config.stream)
        val thinkingPair = checkboxRow("思考模式", preset.config.useThinking)
        return ModelPresetEditor(
            index = index,
            enabledRow = enabledPair.first,
            enabled = enabledPair.second,
            name = field("名称", preset.name),
            type = field("类型", preset.type),
            base = field("Base URL", preset.config.base),
            model = field("模型", preset.config.model),
            key = field("API Key", preset.config.key),
            temperature = field("Temperature", preset.config.temperature.toString()),
            topP = field("Top P", preset.config.topP.toString()),
            maxTokens = field("Max Tokens", preset.config.maxTokens.toString()),
            streamRow = streamPair.first,
            stream = streamPair.second,
            thinkingRow = thinkingPair.first,
            useThinking = thinkingPair.second,
            thinkingEffort = field("思考强度", preset.config.thinkingEffort)
        )
    }

    private fun separator(height: Int = dp(1)): View {
        return View(this).apply {
            setBackgroundColor(Color.argb(16, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        }
    }

    private fun solid(@ColorInt color: Int): GradientDrawable {
        return GradientDrawable().apply { setColor(color) }
    }

    private fun rounded(
        @ColorInt color: Int,
        radius: Int,
        strokeWidth: Int = 0,
        @ColorInt strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private data class FieldRef(val container: View, val input: EditText) {
        fun value(): String = input.text.toString()
    }

    private class ModelPresetEditor(
        val index: Int,
        val enabledRow: LinearLayout,
        val enabled: CheckBox,
        val name: FieldRef,
        val type: FieldRef,
        val base: FieldRef,
        val model: FieldRef,
        val key: FieldRef,
        val temperature: FieldRef,
        val topP: FieldRef,
        val maxTokens: FieldRef,
        val streamRow: LinearLayout,
        val stream: CheckBox,
        val thinkingRow: LinearLayout,
        val useThinking: CheckBox,
        val thinkingEffort: FieldRef
    ) {
        fun applyTo(preset: ModelPreset) {
            preset.enabled = enabled.isChecked
            preset.name = name.value().ifBlank { preset.name }
            preset.type = type.value().trim().lowercase(Locale.ROOT).ifBlank { "openai" }
            preset.config.base = base.value().trim()
            preset.config.model = model.value().trim()
            preset.config.key = key.value().trim()
            preset.config.temperature = temperature.value().toDoubleOrNull() ?: preset.config.temperature
            preset.config.topP = topP.value().toDoubleOrNull() ?: preset.config.topP
            preset.config.maxTokens = maxTokens.value().toIntOrNull() ?: preset.config.maxTokens
            preset.config.maxOutputTokens = preset.config.maxTokens
            preset.config.stream = stream.isChecked
            preset.config.useThinking = useThinking.isChecked
            preset.config.thinkingEffort = thinkingEffort.value().ifBlank { preset.config.thinkingEffort }
        }
    }
}
