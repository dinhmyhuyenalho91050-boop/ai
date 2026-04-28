package com.example.htmlapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.animation.PathInterpolator
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.Spinner
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
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
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
    private val messageThinkingContainers = mutableMapOf<String, View>()
    private val messageThinkingSummaries = mutableMapOf<String, TextView>()
    private val messageRenderedContent = mutableMapOf<String, String>()
    private val messageRenderedThinking = mutableMapOf<String, String>()
    private val messageStreamFormatters = mutableMapOf<String, StreamFormatState>()
    private val streamBodyHeightAnimationTokens = mutableMapOf<String, Int>()
    private val streamBodyHeightAnimators = mutableMapOf<String, ValueAnimator>()
    private val streamBodyHeightTargets = mutableMapOf<String, Int>()
    private val streamBodyLayoutLengths = mutableMapOf<String, Int>()
    private val streamBodyMeasureStates = mutableMapOf<String, StreamBodyMeasureState>()
    private val streamTargets = mutableMapOf<String, StreamRenderTarget>()
    private val messageThinkingExpanded = mutableSetOf<String>()
    private val stoppedAssistantIds = mutableSetOf<String>()
    private var activeAssistantId: String? = null
    private var isSending = false
    private var pendingImportReplace = false
    private var followBottom = true
    private var bottomScrollScheduled = false
    private var scrollTrackScheduled = false
    private var streamAnimatorScheduled = false
    private var userTouchingMessages = false
    private var messageTouchToken = 0
    private var programmaticScroll = false
    private var programmaticScrollReleaseScheduled = false
    private var programmaticScrollReleaseAt = 0L
    private var lastBottomTargetY = -1
    private var settingsCompact = false
    private var streamHeightAnimationSeed = 0

    private val defaultRenderMessageLimit = 80
    private val loadOlderMessageBatch = 40
    private var renderMessageLimit = defaultRenderMessageLimit
    private val scrollTrackFrameMs = 96L
    private val streamFrameMs = 64L
    private val streamDetachedFrameMs = 220L
    private val streamLineFlushChars = 64
    private val streamThinkingFlushChars = 320
    private val streamMaxWaitMs = 180L
    private val streamRevealFrameMs = 24L
    private val streamRevealTouchFrameMs = 80L
    private val streamUiCommitFrameMs = 24L
    private val streamContentCharsPerTick = 1
    private val streamThinkingCharsPerSecond = 300f
    private val streamMaxThinkingCharsPerFrame = 96
    private val softInterpolator by lazy { PathInterpolator(0.2f, 0f, 0f, 1f) }
    private val entranceInterpolator by lazy { PathInterpolator(0.16f, 1f, 0.3f, 1f) }
    private val streamHeightInterpolator by lazy { PathInterpolator(0.2f, 0f, 0.2f, 1f) }

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
        applySystemFontTree(root)
        setupScrollTracking()
        setupBackNavigation()
        renderAll()
        hideSystemBars()
        migrateLegacyWebStateIfNeeded()
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupScrollTracking() {
        messagesScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    messageTouchToken++
                    userTouchingMessages = true
                    followBottom = isNearBottom()
                }
                MotionEvent.ACTION_MOVE -> {
                    scheduleScrollTracking()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val token = ++messageTouchToken
                    followBottom = isNearBottom()
                    mainHandler.postDelayed({
                        if (token == messageTouchToken) userTouchingMessages = false
                    }, 120L)
                }
            }
            false
        }
        messagesScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            scheduleScrollTracking()
        }
        messagesContainer.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (streamBodyHeightAnimationTokens.isEmpty() && followBottom && !userTouchingMessages && bottom != oldBottom) {
                scrollToBottomNow()
            }
        }
    }

    private fun scheduleScrollTracking() {
        if (programmaticScroll || scrollTrackScheduled) return
        scrollTrackScheduled = true
        mainHandler.postDelayed({
            scrollTrackScheduled = false
            if (!programmaticScroll) {
                followBottom = isNearBottom()
            }
        }, scrollTrackFrameMs)
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
        inputMessage.includeFontPadding = true
        inputMessage.setLineSpacing(dp(3).toFloat(), 1.04f)
        inputMessage.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        inputMessage.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
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
            if (followBottom) scrollToBottomSoon()
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
        findViewById<TextView>(R.id.btn_new_chat).setOnClickListener { showPromptSelector() }
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun migrateLegacyWebStateIfNeeded() {
        val prefs = getSharedPreferences("native-chat", Context.MODE_PRIVATE)
        if (prefs.getBoolean("legacy_web_migrated", false)) return
        if (!looksLikeFreshNativeState()) return

        val webView = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        root.addView(webView, FrameLayout.LayoutParams(1, 1))

        fun finishMigration() {
            prefs.edit().putBoolean("legacy_web_migrated", true).apply()
            runCatching {
                root.removeView(webView)
                webView.removeAllViews()
                webView.destroy()
            }
        }

        mainHandler.postDelayed({
            if (webView.parent != null) finishMigration()
        }, 8000)

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onData(jsonText: String) {
                mainHandler.post {
                    runCatching {
                        val data = JSONObject(jsonText)
                        if (hasLegacyPayload(data)) {
                            repository.importBackup(state, data, replace = true)
                            state.ensureSession()
                            renderAll()
                            toast("已迁移旧版 Web 数据")
                        }
                    }.onFailure {
                        toast("旧版数据迁移失败: ${it.message}")
                    }
                    finishMigration()
                }
            }

            @JavascriptInterface
            fun onError(message: String) {
                mainHandler.post {
                    if (message.isNotBlank()) {
                        toast("旧版数据迁移跳过: $message")
                    }
                    finishMigration()
                }
            }
        }, "NativeMigrationBridge")

        webView.loadDataWithBaseURL(
            "https://appassets.androidplatform.net/assets/native-migration.html",
            legacyMigrationHtml(),
            "text/html",
            "UTF-8",
            null
        )
    }

    private fun looksLikeFreshNativeState(): Boolean {
        val promptFresh = state.promptPresets.keys == setOf("default")
        val modelFresh = state.modelPresets.size == 2 &&
            state.modelPresets.all { it.config.key.isBlank() } &&
            state.modelPresets.map { it.name }.containsAll(listOf("GPT-4", "DeepSeek"))
        val sessionFresh = state.sessions.size <= 1 && state.sessions.values.all { it.history.isEmpty() }
        return promptFresh && modelFresh && sessionFresh
    }

    private fun hasLegacyPayload(data: JSONObject): Boolean {
        val models = data.optJSONArray("modelPresets")
        val prompts = data.optJSONObject("promptPresets")
        val sessions = data.optJSONObject("sessions")
        return (models != null && models.length() > 0) ||
            (prompts != null && prompts.length() > 0) ||
            (sessions != null && sessions.length() > 0)
    }

    private fun legacyMigrationHtml(): String {
        return """
            <!doctype html><meta charset="utf-8">
            <script>
            (async function(){
              const keys={
                modelPresets:'chat.modelPresets',
                promptPresets:'chat.promptPresets',
                sessions:'chat.sessions',
                currentId:'chat.currentId',
                sessionIndex:'chat.sessions.index',
                sessionPrefix:'chat.session.'
              };
              function openDb(){
                return new Promise(function(resolve,reject){
                  const req=indexedDB.open('chatAppDB',1);
                  req.onerror=function(){ reject(req.error||new Error('open indexedDB failed')); };
                  req.onsuccess=function(){ resolve(req.result); };
                  req.onupgradeneeded=function(e){
                    const db=e.target.result;
                    if(!db.objectStoreNames.contains('chatData'))db.createObjectStore('chatData');
                  };
                });
              }
              const db=await openDb();
              function get(key,fallback){
                return new Promise(function(resolve){
                  try{
                    const tx=db.transaction(['chatData'],'readonly');
                    const store=tx.objectStore('chatData');
                    const req=store.get(key);
                    req.onsuccess=function(){ resolve(req.result===undefined?fallback:req.result); };
                    req.onerror=function(){ resolve(fallback); };
                  }catch(e){ resolve(fallback); }
                });
              }
              const modelPresets=await get(keys.modelPresets,null);
              const promptPresets=await get(keys.promptPresets,null);
              const currentId=await get(keys.currentId,null);
              let sessions={};
              const index=await get(keys.sessionIndex,null);
              if(Array.isArray(index)&&index.length){
                for(const id of index){
                  const session=await get(keys.sessionPrefix+id,undefined);
                  if(session!==undefined)sessions[id]=session;
                }
              }else{
                sessions=await get(keys.sessions,{})||{};
              }
              NativeMigrationBridge.onData(JSON.stringify({
                version:'9.3',
                timestamp:Date.now(),
                modelPresets:modelPresets,
                promptPresets:promptPresets,
                sessions:sessions,
                currentId:currentId
              }));
            })().catch(function(error){
              NativeMigrationBridge.onError(error&&error.message?error.message:String(error));
            });
            </script>
        """.trimIndent()
    }

    private fun renderAll() {
        state.ensureSession()
        renderModelTabs()
        renderMessages(scrollToBottom = true)
        renderSessions()
        setSendingUi(isSending)
    }

    private fun setSidebarVisible(visible: Boolean) {
        val sidebarWidth = if (sidebar.width > 0) sidebar.width.toFloat() else dp(320).toFloat()
        if (visible) {
            backdrop.alpha = 0f
            backdrop.visibility = View.VISIBLE
            sidebar.translationX = -sidebarWidth
            sidebar.alpha = 0.96f
            sidebar.visibility = View.VISIBLE
            backdrop.animate().alpha(1f).setDuration(360).setInterpolator(softInterpolator).start()
            sidebar.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(480)
                .setInterpolator(entranceInterpolator)
                .start()
        } else {
            backdrop.animate().alpha(0f).setDuration(320).setInterpolator(softInterpolator).withEndAction {
                backdrop.visibility = View.GONE
            }.start()
            sidebar.animate()
                .alpha(0.96f)
                .translationX(-sidebarWidth)
                .setDuration(380)
                .setInterpolator(softInterpolator)
                .withEndAction { sidebar.visibility = View.GONE }
                .start()
        }
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
        val session = state.ensureSession()
        val preset = state.currentPreset()
        session.modelName = preset?.name ?: session.modelName
        val last = session.history.lastOrNull()
        if (text.isEmpty()) {
            when (last?.role) {
                "user" -> {
                    animateSendAction()
                    followBottom = true
                    requestAssistant()
                }
                "assistant" -> {
                    animateSendAction()
                    followBottom = true
                    requestAssistant(continueMessage = last)
                }
            }
            return
        }
        animateSendAction()
        session.history.add(ChatMessage(role = "user", content = text, modelName = preset?.name.orEmpty()))
        followBottom = true
        inputMessage.setText("")
        repository.save(state)
        renderMessages(scrollToBottom = true)
        renderSessions()
        requestAssistant()
    }

    private fun requestAssistant(continueMessage: ChatMessage? = null) {
        val session = state.activeSession() ?: return
        val preset = state.currentPreset()
        val assistant = continueMessage ?: ChatMessage(role = "assistant", content = "", modelName = preset?.name.orEmpty()).also {
            session.history.add(it)
        }
        val continuationPrefill = continueMessage?.content.orEmpty()
        val promptForRegex = state.promptFor(session).copy()
        assistant.modelName = preset?.name.orEmpty()
        followBottom = true
        activeAssistantId = assistant.id
        repository.save(state)
        setSendingUi(true)
        renderMessages(scrollToBottom = true)

        Thread {
            var lastFrameAt = 0L
            var latestContent = ""
            var latestThinking = ""
            var latestDisplayContent = continuationPrefill
            var renderedContentLength = continuationPrefill.length
            var renderedThinkingLength = assistant.thinking.length
            val uiUpdatePosted = AtomicBoolean(false)
            runCatching {
                aiClient.send(state, session, continuationPrefill.takeIf { it.isNotBlank() }) { content, thinking ->
                    latestContent = content
                    latestThinking = thinking
                    latestDisplayContent = mergeContinuationContent(continuationPrefill, content)
                    val now = android.os.SystemClock.uptimeMillis()
                    val frameMs = if (followBottom) streamFrameMs else streamDetachedFrameMs
                    val contentDelta = (latestDisplayContent.length - renderedContentLength).coerceAtLeast(0)
                    val thinkingDelta = (latestThinking.length - renderedThinkingLength).coerceAtLeast(0)
                    val contentStart = renderedContentLength.coerceIn(0, latestDisplayContent.length)
                    val thinkingStart = renderedThinkingLength.coerceIn(0, latestThinking.length)
                    val hasLineBreak = latestDisplayContent.indexOf('\n', contentStart) >= 0 ||
                        latestThinking.indexOf('\n', thinkingStart) >= 0
                    val hasLineChunk = contentDelta >= streamLineFlushChars ||
                        thinkingDelta >= streamThinkingFlushChars
                    val waitedLongEnough = now - lastFrameAt >= min(frameMs, streamMaxWaitMs)
                    if ((hasLineBreak || hasLineChunk || waitedLongEnough) &&
                        uiUpdatePosted.compareAndSet(false, true)
                    ) {
                        lastFrameAt = now
                        mainHandler.post {
                            uiUpdatePosted.set(false)
                            assistant.content = latestDisplayContent
                            assistant.thinking = latestThinking
                            renderedContentLength = assistant.content.length
                            renderedThinkingLength = assistant.thinking.length
                            updateStreamTarget(assistant, latestDisplayContent, latestThinking)
                        }
                }
            }
            }.onSuccess { result ->
                val finalContent = applyPromptRegex(
                    mergeContinuationContent(continuationPrefill, result.content),
                    promptForRegex
                )
                mainHandler.post {
                    stoppedAssistantIds.remove(assistant.id)
                    if (activeAssistantId == assistant.id) activeAssistantId = null
                    assistant.content = finalContent
                    assistant.thinking = result.thinking
                    repository.save(state)
                    setSendingUi(false)
                    if (preset?.config?.stream == true || streamTargets.containsKey(assistant.id)) {
                        updateStreamTarget(assistant, assistant.content, assistant.thinking, finishWhenCaught = true)
                    } else {
                        updateMessageViews(assistant, final = true)
                    }
                    renderSessions()
                }
            }.onFailure { error ->
                mainHandler.post {
                    val stopped = stoppedAssistantIds.remove(assistant.id)
                    if (activeAssistantId == assistant.id) activeAssistantId = null
                    if (stopped) {
                        val partialContent = latestDisplayContent.ifBlank { assistant.content }
                        val partialThinking = latestThinking.ifBlank { assistant.thinking }
                        if (continueMessage == null && partialContent.isBlank() && partialThinking.isBlank()) {
                            session.history.removeAll { it.id == assistant.id }
                            repository.save(state)
                            setSendingUi(false)
                            renderMessages(scrollToBottom = followBottom)
                        } else {
                            assistant.content = applyPromptRegex(partialContent)
                            assistant.thinking = partialThinking
                            repository.save(state)
                            setSendingUi(false)
                            updateStreamTarget(assistant, assistant.content, assistant.thinking, finishWhenCaught = true)
                        }
                    } else {
                        streamTargets.remove(assistant.id)
                        val failureText = if (error.message.isNullOrBlank()) "请求失败" else "请求失败: ${error.message}"
                        if (continueMessage == null) {
                            assistant.content = failureText
                            assistant.thinking = ""
                        } else {
                            assistant.content = latestDisplayContent.ifBlank { continuationPrefill }
                            toast(failureText)
                        }
                        repository.save(state)
                        setSendingUi(false)
                        updateMessageViews(assistant, final = true)
                    }
                    renderSessions()
                }
            }
        }.start()
    }

    private fun mergeContinuationContent(prefix: String, generated: String): String {
        if (prefix.isBlank()) return generated
        if (generated.isBlank()) return prefix
        return if (generated.startsWith(prefix)) generated else prefix + generated
    }

    private fun stopSending() {
        activeAssistantId?.let { stoppedAssistantIds.add(it) }
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

    private fun animateSendAction() {
        sendButton.animate().cancel()
        sendButton.pivotX = sendButton.width / 2f
        sendButton.pivotY = sendButton.height / 2f
        sendButton.animate()
            .scaleX(0.92f)
            .scaleY(0.92f)
            .translationY(dp(2).toFloat())
            .setDuration(110)
            .setInterpolator(softInterpolator)
            .withEndAction {
                sendButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(380)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
                    .start()
            }
            .start()
    }

    private fun showPromptSelector() {
        if (isSending) return
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(dialogTitle("选择提示词预设"))
        }
        state.promptPresets.keys.sorted().forEach { key ->
            val preset = state.promptPresets[key] ?: return@forEach
            list.addView(promptPresetSelectCard(key, preset).apply {
                setOnClickListener {
                    dialog.dismiss()
                    createNewChat(key)
                }
            })
        }
        list.addView(dialogButton("取消").apply { setOnClickListener { dialog.dismiss() } })

        val scroll = ScrollView(this).apply {
            addView(list)
            background = rounded(color(R.color.chat_panel), dp(16), dp(1), color(R.color.chat_border))
        }
        dialog.setContentView(scroll)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                min(resources.displayMetrics.widthPixels - dp(32), dp(700)),
                min(resources.displayMetrics.heightPixels - dp(96), dp(620))
            )
            scroll.alpha = 0f
            scroll.scaleX = 0.96f
            scroll.scaleY = 0.96f
            scroll.translationY = dp(10).toFloat()
            scroll.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(460)
                .setInterpolator(entranceInterpolator)
                .start()
        }
        dialog.show()
    }

    private fun promptPresetSelectCard(key: String, preset: PromptPreset): View {
        val desc = listOf(preset.firstUser, preset.firstAssistant, preset.sysPrompt)
            .firstOrNull { it.isNotBlank() }
            ?.take(80)
            ?: "暂无描述"
        val rules = runCatching { JSONArray(preset.regexRulesJson).length() }.getOrDefault(0)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color(R.color.chat_card), dp(12), dp(1), color(R.color.chat_border))
            setPadding(dp(14), dp(13), dp(14), dp(13))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            addView(TextView(context).apply {
                text = preset.name.ifBlank { key }
                setTextColor(color(R.color.chat_fg))
                textSize = 14f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = desc
                setTextColor(color(R.color.chat_muted))
                textSize = 12f
                setPadding(0, dp(8), 0, 0)
            })
            addView(TextView(context).apply {
                text = "$rules 个规则"
                setTextColor(color(R.color.chat_accent_blue))
                textSize = 11f
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun createNewChat(presetKey: String = "default") {
        if (isSending) return
        val preset = state.currentPreset()
        val resolvedKey = if (state.promptPresets.containsKey(presetKey)) presetKey else "default"
        val prompt = state.promptPresets[resolvedKey] ?: PromptPreset()
        val session = ChatSession(
            name = "对话 ${state.sessions.size + 1}",
            modelName = preset?.name ?: "未知",
            promptPresetName = resolvedKey
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
        renderMessageLimit = defaultRenderMessageLimit
        repository.save(state)
        inputMessage.setText("")
        renderAll()
        setSidebarVisible(false)
    }

    private fun renderMessages(scrollToBottom: Boolean = false) {
        streamBodyHeightAnimators.values.toList().forEach { it.cancel() }
        streamBodyHeightAnimators.clear()
        streamBodyHeightAnimationTokens.clear()
        streamBodyHeightTargets.clear()
        streamBodyLayoutLengths.clear()
        streamBodyMeasureStates.clear()
        messageBodyViews.clear()
        messageThinkingViews.clear()
        messageThinkingContainers.clear()
        messageThinkingSummaries.clear()
        messageRenderedContent.clear()
        messageRenderedThinking.clear()
        messageStreamFormatters.clear()
        messagesContainer.removeAllViews()
        val session = state.ensureSession()
        val startIndex = max(0, session.history.size - renderMessageLimit)
        if (startIndex > 0) {
            messagesContainer.addView(loadOlderHint(startIndex))
        }
        session.history.drop(startIndex).forEachIndexed { offset, message ->
            val card = messageCard(message, startIndex + offset + 1)
            messagesContainer.addView(card)
            if (scrollToBottom && offset >= session.history.size - startIndex - 2) {
                animateIn(card)
            }
        }
        if (scrollToBottom) {
            scrollToBottomSoon(animated = false)
        }
    }

    private fun loadOlderHint(hiddenCount: Int): View {
        return TextView(this).apply {
            text = "加载更早消息 · 还有 $hiddenCount 条"
            setTextColor(color(R.color.chat_muted))
            textSize = 12f
            gravity = Gravity.CENTER
            background = rounded(color(R.color.chat_subtle_surface), dp(12), dp(1), color(R.color.chat_border))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener {
                renderMessageLimit += loadOlderMessageBatch
                renderMessages(scrollToBottom = false)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
    }

    private fun messageCard(message: ChatMessage, index: Int): View {
        val isAssistant = message.role == "assistant"
        val accent = if (isAssistant) Color.rgb(100, 210, 255) else color(R.color.chat_accent_blue)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(color(R.color.chat_card), dp(14), dp(1), color(R.color.chat_border))
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

        contentColumn.addView(thinkingPanel(message))

        val body = TextView(this).apply {
            val content = displayContent(message)
            if (isLiveAssistant(message)) {
                setText(streamingFormatter(message.id).render(content).text, TextView.BufferType.SPANNABLE)
            } else {
                text = formatMessageText(content)
            }
            setTextColor(color(R.color.chat_fg))
            textSize = 16f
            typeface = systemTypeface()
            includeFontPadding = true
            setLineSpacing(dp(4).toFloat(), 1.04f)
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            setPadding(dp(16), dp(18), dp(16), dp(18))
            messageRenderedContent[message.id] = content
        }
        messageBodyViews[message.id] = body
        contentColumn.addView(body)
        contentColumn.addView(separator())
        contentColumn.addView(messageFooter(message))
        card.addView(contentColumn)
        return card
    }

    private fun thinkingPanel(message: ChatMessage): View {
        val expanded = message.id in messageThinkingExpanded
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (message.thinking.isBlank()) View.GONE else View.VISIBLE
            background = rounded(color(R.color.chat_thinking_bg), dp(8), dp(1), color(R.color.chat_thinking_border))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(dp(16), dp(14), dp(16), 0)
            }
        }
        val summary = TextView(this).apply {
            text = thinkingSummaryText(message.thinking, expanded)
            setTextColor(color(R.color.chat_accent_blue))
            textSize = 13f
            typeface = systemTypeface(Typeface.BOLD)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { setThinkingExpanded(message.id, message.id !in messageThinkingExpanded) }
        }
        val content = TextView(this).apply {
            text = if (expanded) message.thinking else ""
            visibility = if (expanded) View.VISIBLE else View.GONE
            setTextColor(color(R.color.chat_muted))
            textSize = 13f
            typeface = systemTypeface()
            includeFontPadding = true
            setLineSpacing(dp(3).toFloat(), 1.04f)
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            setPadding(0, dp(10), 0, 0)
        }
        panel.addView(summary)
        panel.addView(content)
        messageThinkingContainers[message.id] = panel
        messageThinkingSummaries[message.id] = summary
        messageThinkingViews[message.id] = content
        messageRenderedThinking[message.id] = message.thinking
        return panel
    }

    private fun thinkingSummaryText(thinking: String, expanded: Boolean): String {
        val suffix = if (expanded) "点击折叠" else "点击展开"
        return "💭 思维链 (${thinking.length}字) · $suffix"
    }

    private fun setThinkingExpanded(messageId: String, expanded: Boolean) {
        if (expanded) {
            messageThinkingExpanded.add(messageId)
        } else {
            messageThinkingExpanded.remove(messageId)
        }
        val thinking = messageRenderedThinking[messageId].orEmpty()
        messageThinkingSummaries[messageId]?.text = thinkingSummaryText(thinking, expanded)
        messageThinkingViews[messageId]?.apply {
            text = if (expanded) thinking else ""
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        if (expanded && followBottom) scrollToBottomSoon()
    }

    private fun displayContent(message: ChatMessage): String {
        return if (message.role == "assistant" && message.content.isBlank() && isSending) "正在思考..." else message.content
    }

    private fun updateMessageViews(message: ChatMessage, streaming: Boolean = false, final: Boolean = false) {
        val stick = !userTouchingMessages && (followBottom || isNearBottom())
        updateBodyText(message, streaming, final)
        updateThinkingPanel(message)
        if (stick) {
            if (streaming) scrollToBottomNow() else scrollToBottomSoon()
        }
    }

    private fun updateBodyText(message: ChatMessage, streaming: Boolean, final: Boolean) {
        val body = messageBodyViews[message.id] ?: return
        val content = displayContent(message)
        val previous = messageRenderedContent[message.id]
        if (streaming && !final) {
            if (previous == content) return
            val renderResult = streamingFormatter(message.id).render(content)
            val hasNewLine = previous == null ||
                content.length < previous.length ||
                content.indexOf('\n', previous.length.coerceIn(0, content.length)) >= 0
            setStreamingBodyText(
                message.id,
                body,
                renderResult.text,
                visibleLength = content.length,
                hasNewLine = hasNewLine,
                changedStart = renderResult.changedStart
            )
            messageRenderedContent[message.id] = content
            return
        }
        if (final || previous != content) {
            messageStreamFormatters.remove(message.id)
            streamBodyLayoutLengths.remove(message.id)
            streamBodyMeasureStates.remove(message.id)
            stopStreamingBodyHeightAnimation(message.id, body)
            body.text = formatMessageText(content)
            messageRenderedContent[message.id] = content
        }
    }

    private fun setStreamingBodyText(
        messageId: String,
        body: TextView,
        text: CharSequence,
        visibleLength: Int,
        hasNewLine: Boolean,
        changedStart: Int
    ) {
        val oldHeight = currentBodyHeight(body)
        val oldWidth = body.width
        if (oldHeight > 0 && oldWidth > 0) {
            pinBodyHeight(body, oldHeight)
        }
        body.setText(text, TextView.BufferType.SPANNABLE)
        if (oldHeight <= 0 || oldWidth <= 0) {
            ensureBodyWrapContent(body)
            return
        }
        val targetHeight = estimateStreamingTextHeight(messageId, body, oldWidth, text, changedStart, hasNewLine)
        val activeTarget = streamBodyHeightTargets[messageId]
        val activeAnimation = streamBodyHeightAnimationTokens[messageId]
        if (activeTarget == targetHeight && activeAnimation != null) {
            return
        }
        val fromHeight = currentBodyHeight(body)
        if (targetHeight > fromHeight + dp(1) && targetHeight > (activeTarget ?: 0) + dp(1)) {
            streamBodyLayoutLengths[messageId] = text.length
            animateStreamingBodyHeight(messageId, body, fromHeight, targetHeight)
            return
        }
        if (activeTarget == null && activeAnimation == null) {
            pinBodyHeight(body, max(fromHeight, targetHeight))
        }
    }

    private fun currentBodyHeight(body: TextView): Int {
        val pinned = body.layoutParams?.height ?: 0
        return if (pinned > 0) pinned else body.height
    }

    private fun estimateStreamingTextHeight(
        messageId: String,
        body: TextView,
        width: Int,
        text: CharSequence,
        changedStart: Int,
        hasNewLine: Boolean
    ): Int {
        val state = streamBodyMeasureStates.getOrPut(messageId) { StreamBodyMeasureState() }
        val reset = state.width != width ||
            text.length < state.textLength ||
            changedStart < state.textLength ||
            state.lineHeight <= 0 ||
            hasNewLine
        val start = if (reset) {
            resetStreamingLineEstimate(state, body, width)
            0
        } else {
            state.textLength
        }
        for (index in start until text.length) {
            addEstimatedChar(state, text[index])
        }
        state.textLength = text.length
        state.targetHeight = state.baseHeight + state.estimatedLines * state.lineHeight
        return state.targetHeight
    }

    private fun resetStreamingLineEstimate(state: StreamBodyMeasureState, body: TextView, width: Int) {
        val contentWidth = (width - body.paddingLeft - body.paddingRight).coerceAtLeast(dp(80))
        val averageCharWidth = body.paint.measureText("中").coerceAtLeast(1f)
        state.width = width
        state.textLength = 0
        state.estimatedLines = 1
        state.lineUnits = 0f
        state.unitsPerLine = (contentWidth / averageCharWidth).coerceAtLeast(4f)
        state.lineHeight = body.lineHeight.coerceAtLeast(1)
        state.baseHeight = body.paddingTop + body.paddingBottom + dp(3)
        state.targetHeight = state.baseHeight + state.lineHeight
    }

    private fun addEstimatedChar(state: StreamBodyMeasureState, ch: Char) {
        if (ch == '\r') return
        if (ch == '\n') {
            state.estimatedLines += 1
            state.lineUnits = 0f
            return
        }
        val units = estimatedCharUnits(ch)
        if (state.lineUnits > 0f && state.lineUnits + units > state.unitsPerLine) {
            state.estimatedLines += 1
            state.lineUnits = units
        } else {
            state.lineUnits += units
        }
    }

    private fun estimatedCharUnits(ch: Char): Float {
        if (Character.isLowSurrogate(ch)) return 0f
        if (Character.isHighSurrogate(ch)) return 1.1f
        return when {
            ch.code <= 0x7F && ch.isWhitespace() -> 0.35f
            ch.code <= 0x7F && ch.isLetterOrDigit() -> 0.68f
            ch.code <= 0x7F -> 0.5f
            ch in '\uFF61'..'\uFF9F' -> 0.65f
            else -> 1f
        }
    }

    private fun streamingHeightCheckStep(body: TextView, width: Int): Int {
        val contentWidth = (width - body.paddingLeft - body.paddingRight).coerceAtLeast(dp(80))
        val averageCharWidth = body.paint.measureText("中").coerceAtLeast(1f)
        val estimatedCharsPerLine = (contentWidth / averageCharWidth).toInt().coerceAtLeast(8)
        return (estimatedCharsPerLine / 6).coerceIn(3, 8)
    }

    private fun measureCurrentTextHeight(body: TextView, width: Int): Int {
        val contentWidth = (width - body.paddingLeft - body.paddingRight).coerceAtLeast(1)
        val text = body.text ?: ""
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, body.paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(body.lineSpacingExtra, body.lineSpacingMultiplier)
            .setIncludePad(body.includeFontPadding)
            .setBreakStrategy(body.breakStrategy)
            .setHyphenationFrequency(body.hyphenationFrequency)
            .build()
        return layout.height + body.paddingTop + body.paddingBottom
    }

    private fun animateStreamingBodyHeight(messageId: String, body: TextView, fromHeight: Int, toHeight: Int) {
        streamBodyHeightAnimators.remove(messageId)?.cancel()
        val token = ++streamHeightAnimationSeed
        streamBodyHeightAnimationTokens[messageId] = token
        streamBodyHeightTargets[messageId] = toHeight
        pinBodyHeight(body, fromHeight)
        if (userTouchingMessages) {
            finishStreamingBodyHeightAnimation(messageId, body, token, toHeight)
            return
        }
        val delta = toHeight - fromHeight
        if (delta <= dp(1)) {
            finishStreamingBodyHeightAnimation(messageId, body, token, toHeight)
            return
        }
        val durationMs = (delta * 2L).coerceIn(150L, 260L)
        var cancelled = false
        val animator = ValueAnimator.ofInt(fromHeight, toHeight).apply {
            duration = durationMs
            interpolator = streamHeightInterpolator
            addUpdateListener { animation ->
                if (streamBodyHeightAnimationTokens[messageId] != token) return@addUpdateListener
                pinBodyHeight(body, animation.animatedValue as Int)
                if (followBottom && !userTouchingMessages) scrollToBottomNow()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    streamBodyHeightAnimators.remove(messageId)
                    if (!cancelled) {
                        finishStreamingBodyHeightAnimation(messageId, body, token, toHeight)
                    }
                }
            })
        }
        streamBodyHeightAnimators[messageId] = animator
        animator.start()
    }

    private fun finishStreamingBodyHeightAnimation(messageId: String, body: TextView, token: Int, toHeight: Int) {
        if (streamBodyHeightAnimationTokens[messageId] != token) return
        streamBodyHeightAnimators.remove(messageId)
        streamBodyHeightAnimationTokens.remove(messageId)
        streamBodyHeightTargets.remove(messageId)
        streamBodyLayoutLengths.remove(messageId)
        pinBodyHeight(body, toHeight)
        if (followBottom && !userTouchingMessages) scrollToBottomNow()
    }

    private fun stopStreamingBodyHeightAnimation(messageId: String, body: TextView) {
        streamBodyHeightAnimators.remove(messageId)?.cancel()
        streamBodyHeightAnimationTokens.remove(messageId)
        streamBodyHeightTargets.remove(messageId)
        streamBodyLayoutLengths.remove(messageId)
        streamBodyMeasureStates.remove(messageId)
        ensureBodyWrapContent(body)
    }

    private fun pinBodyHeight(body: TextView, height: Int) {
        val params = body.layoutParams ?: return
        if (params.height != height) {
            params.height = height
            body.layoutParams = params
        }
    }

    private fun ensureBodyWrapContent(body: TextView) {
        val params = body.layoutParams ?: return
        if (params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            body.layoutParams = params
        }
    }

    private fun updateStreamTarget(
        message: ChatMessage,
        content: String,
        thinking: String,
        finishWhenCaught: Boolean = false
    ) {
        val target = streamTargets.getOrPut(message.id) {
            StreamRenderTarget(
                message = message,
                visibleContent = messageRenderedContent[message.id].orEmpty().takeIf { content.startsWith(it) }.orEmpty(),
                visibleThinking = messageRenderedThinking[message.id].orEmpty().takeIf { thinking.startsWith(it) }.orEmpty()
            )
        }
        target.message = message
        target.contentPrefixValid = contentHasVisiblePrefix(target, content)
        target.thinkingPrefixValid = thinkingHasVisiblePrefix(target, thinking)
        if (content != target.targetContent) {
            target.releaseScanStart = -1
            target.releaseScanLength = -1
            target.releaseScanBoundary = -1
        }
        target.targetContent = content
        target.targetThinking = thinking
        target.lastTargetUpdateAt = android.os.SystemClock.uptimeMillis()
        if (target.contentPrefixValid) {
            target.contentReleaseEnd = max(target.contentReleaseEnd, target.visibleContentLength)
                .coerceAtMost(target.targetContent.length)
        }
        if (target.targetContent.length > target.contentReleaseEnd && target.unreleasedContentSince == 0L) {
            target.unreleasedContentSince = target.lastTargetUpdateAt
        }
        target.finishWhenCaught = target.finishWhenCaught || finishWhenCaught
        scheduleStreamAnimator()
    }

    private fun contentHasVisiblePrefix(target: StreamRenderTarget, content: String): Boolean {
        if (content.length < target.visibleContentLength) return false
        if (target.targetContent.isNotEmpty() &&
            content.length >= target.targetContent.length &&
            content.startsWith(target.targetContent)
        ) {
            return true
        }
        if (target.visibleContentLength > target.visibleContent.length) return false
        return content.startsWith(target.visibleContent)
    }

    private fun thinkingHasVisiblePrefix(target: StreamRenderTarget, thinking: String): Boolean {
        if (thinking.length < target.visibleThinkingLength) return false
        if (target.targetThinking.isNotEmpty() &&
            thinking.length >= target.targetThinking.length &&
            thinking.startsWith(target.targetThinking)
        ) {
            return true
        }
        if (target.visibleThinkingLength > target.visibleThinking.length) return false
        return thinking.startsWith(target.visibleThinking)
    }

    private fun scheduleStreamAnimator() {
        if (streamAnimatorScheduled) return
        streamAnimatorScheduled = true
        val delayMs = if (userTouchingMessages) streamRevealTouchFrameMs else streamRevealFrameMs
        val tick = Runnable {
            streamAnimatorScheduled = false
            tickStreamAnimator()
        }
        if (!userTouchingMessages && delayMs <= 8L) {
            ViewCompat.postOnAnimation(root, tick)
        } else {
            ViewCompat.postOnAnimationDelayed(root, tick, delayMs)
        }
    }

    private fun tickStreamAnimator() {
        val now = android.os.SystemClock.uptimeMillis()
        var needsNextFrame = false
        val iterator = streamTargets.entries.iterator()
        while (iterator.hasNext()) {
            val target = iterator.next().value
            refreshContentReleaseEnd(target)
            val elapsedMs = revealElapsedMs(target, now)
            val thinkingBudget = revealBudget(
                target.thinkingRevealCarry,
                streamThinkingCharsPerSecond,
                elapsedMs,
                streamMaxThinkingCharsPerFrame
            )
            target.thinkingRevealCarry = thinkingBudget.carry
            val contentEnd = target.contentReleaseEnd.coerceIn(0, target.targetContent.length)
            val nextContentLength = if (target.contentPrefixValid) {
                val step = if (target.visibleContentLength < contentEnd) streamContentCharsPerTick else 0
                advanceVisibleLength(target.visibleContentLength, contentEnd, step)
            } else {
                contentEnd
            }
            val nextThinkingLength = if (target.thinkingPrefixValid) {
                advanceVisibleLength(target.visibleThinkingLength, target.targetThinking.length, thinkingBudget.chars)
            } else {
                target.targetThinking.length
            }
            val contentChanged = nextContentLength != target.visibleContentLength || !target.contentPrefixValid
            val thinkingChanged = nextThinkingLength != target.visibleThinkingLength || !target.thinkingPrefixValid
            val changed = contentChanged || thinkingChanged
            target.visibleContentLength = nextContentLength
            target.visibleThinkingLength = nextThinkingLength
            if (changed) {
                if (shouldCommitStreamFrame(target, now, contentChanged, thinkingChanged, nextContentLength, nextThinkingLength)) {
                    val nextContent = target.targetContent.prefixAt(nextContentLength)
                    val nextThinking = target.targetThinking.prefixAt(nextThinkingLength)
                    target.visibleContent = nextContent
                    target.visibleThinking = nextThinking
                    target.contentPrefixValid = true
                    target.thinkingPrefixValid = true
                    target.lastUiCommitAt = now
                    target.lastRevealAt = now
                    val display = target.message.copy(content = nextContent, thinking = nextThinking)
                    updateMessageViews(display, streaming = true)
                } else {
                    needsNextFrame = true
                }
            }
            val caughtUp = nextContentLength == target.targetContent.length && nextThinkingLength == target.targetThinking.length
            if (caughtUp && target.finishWhenCaught) {
                iterator.remove()
                messageStreamFormatters.remove(target.message.id)
                updateMessageViews(target.message, final = true)
            } else if (!caughtUp || target.targetContent.length > target.contentReleaseEnd) {
                needsNextFrame = true
            }
        }
        if (needsNextFrame) scheduleStreamAnimator()
    }

    private fun refreshContentReleaseEnd(target: StreamRenderTarget) {
        val length = target.targetContent.length
        target.contentReleaseEnd = length
        target.unreleasedContentSince = 0L
    }

    private fun cachedParagraphBoundaryAfter(target: StreamRenderTarget, start: Int): Int {
        val length = target.targetContent.length
        if (target.releaseScanStart == start && target.releaseScanLength == length) {
            return target.releaseScanBoundary
        }
        val boundary = findParagraphBoundaryAfter(target.targetContent, start)
        target.releaseScanStart = start
        target.releaseScanLength = length
        target.releaseScanBoundary = boundary
        return boundary
    }

    private fun findParagraphBoundaryAfter(text: String, start: Int): Int {
        for (i in start until text.length) {
            if (text[i] == '\n') {
                var end = i + 1
                while (end < text.length && text[end] == '\n') end++
                return end
            }
        }
        return -1
    }

    private fun softReleaseEnd(text: String, start: Int, end: Int): Int {
        var boundary = -1
        val minEnd = min(end, start + 32)
        for (i in start until end) {
            if (i >= minEnd && isSentenceEnd(text[i])) boundary = i + 1
        }
        return if (boundary > start) boundary else end
    }

    private fun isSentenceEnd(ch: Char): Boolean {
        return ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?'
    }

    private fun revealElapsedMs(target: StreamRenderTarget, now: Long): Long {
        val previous = target.lastTickAt
        target.lastTickAt = now
        if (previous <= 0L) return streamRevealFrameMs
        return (now - previous).coerceIn(1L, streamRevealTouchFrameMs)
    }

    private fun revealBudget(carry: Float, charsPerSecond: Float, elapsedMs: Long, maxChars: Int): RevealBudget {
        val available = carry + charsPerSecond * elapsedMs / 1000f
        val chars = min(maxChars, available.toInt())
        val nextCarry = (available - chars).coerceIn(0f, maxChars.toFloat())
        return RevealBudget(chars, nextCarry)
    }

    private fun shouldCommitStreamFrame(
        target: StreamRenderTarget,
        now: Long,
        contentChanged: Boolean,
        thinkingChanged: Boolean,
        nextContentLength: Int,
        nextThinkingLength: Int
    ): Boolean {
        if (!contentChanged && !thinkingChanged) return false
        if (!target.contentPrefixValid || !target.thinkingPrefixValid) return true
        if (target.lastUiCommitAt == 0L) return true
        if (target.finishWhenCaught &&
            nextContentLength == target.targetContent.length &&
            nextThinkingLength == target.targetThinking.length
        ) {
            return true
        }
        val minInterval = if (userTouchingMessages) streamRevealTouchFrameMs else streamUiCommitFrameMs
        return now - target.lastUiCommitAt >= minInterval
    }

    private fun advanceVisibleLength(current: Int, targetEnd: Int, maxChars: Int): Int {
        val end = targetEnd.coerceAtLeast(0)
        if (current == end) return current
        if (current > end) return end
        if (maxChars <= 0) return current
        val remaining = end - current
        if (remaining <= maxChars) return end
        return min(end, current + maxChars)
    }

    private fun String.prefixAt(length: Int): String {
        val end = length.coerceIn(0, this.length)
        return if (end == this.length) this else substring(0, end)
    }

    private fun streamingFormatter(messageId: String): StreamFormatState {
        return messageStreamFormatters.getOrPut(messageId) {
            StreamFormatState(
                quoteColor = color(R.color.chat_accent3),
                starColor = color(R.color.chat_accent)
            )
        }
    }

    private fun updateThinkingPanel(message: ChatMessage) {
        val panel = messageThinkingContainers[message.id] ?: return
        val summary = messageThinkingSummaries[message.id] ?: return
        val content = messageThinkingViews[message.id] ?: return
        val thinking = message.thinking
        val expanded = message.id in messageThinkingExpanded
        if (messageRenderedThinking[message.id] == thinking) return
        messageRenderedThinking[message.id] = thinking
        panel.visibility = if (thinking.isBlank()) View.GONE else View.VISIBLE
        summary.text = thinkingSummaryText(thinking, expanded)
        if (thinking.isBlank()) {
            content.text = ""
            content.visibility = View.GONE
        } else if (expanded) {
            if (content.text.toString() != thinking) {
                content.text = thinking
            }
            content.visibility = View.VISIBLE
        } else {
            if (content.text.isNotEmpty()) {
                content.text = ""
            }
            content.visibility = View.GONE
        }
    }

    private fun isLiveAssistant(message: ChatMessage): Boolean {
        return isSending &&
            message.role == "assistant" &&
            state.activeSession()?.history?.lastOrNull()?.id == message.id
    }

    private fun formatMessageText(text: String): CharSequence {
        if (text.isEmpty()) return text
        val out = SpannableStringBuilder()
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                isQuote(ch) -> {
                    val end = findNext(text, index + 1, ::isQuote)
                    if (end > index + 1) {
                        val start = out.length
                        out.append(text, index, end + 1)
                        out.setSpan(ForegroundColorSpan(color(R.color.chat_accent3)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        index = end + 1
                    } else {
                        out.append(ch)
                        index += 1
                    }
                }
                isStar(ch) -> {
                    val end = findNext(text, index + 1, ::isStar)
                    if (end > index + 1) {
                        val start = out.length
                        out.append(text, index + 1, end)
                        out.setSpan(ForegroundColorSpan(color(R.color.chat_accent)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        index = end + 1
                    } else {
                        out.append(ch)
                        index += 1
                    }
                }
                else -> {
                    out.append(ch)
                    index += 1
                }
            }
        }
        return out
    }

    private fun applyPromptRegex(content: String): String {
        return applyPromptRegex(content, state.promptFor(state.activeSession()))
    }

    private fun applyPromptRegex(content: String, prompt: PromptPreset): String {
        if (content.isBlank()) return content
        val rules = runCatching { JSONArray(prompt.regexRulesJson) }.getOrNull() ?: return content
        var result = content
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val pattern = rule.optString("pattern")
            if (pattern.isBlank()) continue
            val replacement = rule.optString("replacement")
            val compiled = compilePromptRegex(pattern, rule.optString("flags"))
            result = compiled?.let {
                runCatching {
                    if (it.second) it.first.replace(result, replacement) else it.first.replaceFirst(result, replacement)
                }.getOrDefault(result)
            } ?: result
        }
        return result
    }

    private fun compilePromptRegex(rawPattern: String, rawFlags: String): Pair<Regex, Boolean>? {
        var pattern = rawPattern
        var flags = rawFlags
        val literal = Regex("^/([\\s\\S]*)/([a-zA-Z]*)$").matchEntire(pattern)
        if (literal != null) {
            pattern = literal.groupValues[1]
            flags = literal.groupValues[2]
        }
        val options = buildSet {
            if ('i' in flags) add(RegexOption.IGNORE_CASE)
            if ('m' in flags) add(RegexOption.MULTILINE)
            if ('s' in flags) add(RegexOption.DOT_MATCHES_ALL)
        }
        return runCatching { Regex(pattern, options) to ('g' in flags) }.getOrNull()
    }

    private fun isQuote(ch: Char): Boolean {
        return ch == '"' || ch == '\u201C' || ch == '\u201D' || ch == '\uFF02'
    }

    private fun isStar(ch: Char): Boolean {
        return ch == '*' || ch == '\uFF0A'
    }

    private fun findNext(text: String, start: Int, predicate: (Char) -> Boolean): Int {
        for (i in start until text.length) {
            if (predicate(text[i])) return i
        }
        return -1
    }

    private fun isNearBottom(): Boolean {
        val child = messagesScroll.getChildAt(0) ?: return true
        val distance = child.bottom - (messagesScroll.scrollY + messagesScroll.height)
        return distance <= dp(60)
    }

    private fun scrollToBottomSoon(animated: Boolean = false) {
        if (userTouchingMessages) return
        if (bottomScrollScheduled) return
        bottomScrollScheduled = true
        messagesScroll.post {
            bottomScrollScheduled = false
            val child = messagesScroll.getChildAt(0)
            if (child == null) return@post
            val targetY = max(0, child.bottom - messagesScroll.height)
            if (targetY == lastBottomTargetY && abs(messagesScroll.scrollY - targetY) <= dp(1)) {
                followBottom = true
                return@post
            }
            lastBottomTargetY = targetY
            keepProgrammaticScrollActive()
            if (animated) {
                messagesScroll.smoothScrollTo(0, targetY)
            } else {
                messagesScroll.scrollTo(0, targetY)
            }
            followBottom = true
        }
    }

    private fun scrollToBottomNow() {
        if (userTouchingMessages) return
        val child = messagesScroll.getChildAt(0) ?: return
        val targetY = max(0, child.bottom - messagesScroll.height)
        if (targetY == lastBottomTargetY && abs(messagesScroll.scrollY - targetY) <= dp(1)) {
            followBottom = true
            return
        }
        lastBottomTargetY = targetY
        keepProgrammaticScrollActive()
        messagesScroll.scrollTo(0, targetY)
        followBottom = true
    }

    private fun keepProgrammaticScrollActive() {
        programmaticScroll = true
        programmaticScrollReleaseAt = android.os.SystemClock.uptimeMillis() + scrollTrackFrameMs
        if (programmaticScrollReleaseScheduled) return
        programmaticScrollReleaseScheduled = true
        fun releaseWhenQuiet() {
            val remaining = programmaticScrollReleaseAt - android.os.SystemClock.uptimeMillis()
            if (remaining > 0L) {
                mainHandler.postDelayed({ releaseWhenQuiet() }, remaining)
            } else {
                programmaticScrollReleaseScheduled = false
                programmaticScroll = false
                followBottom = true
            }
        }
        mainHandler.postDelayed({ releaseWhenQuiet() }, scrollTrackFrameMs)
    }

    private fun animateIn(view: View) {
        view.alpha = 0f
        view.scaleX = 0.985f
        view.scaleY = 0.985f
        view.translationY = dp(18).toFloat()
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(560)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
            .start()
    }

    private fun messageHeader(message: ChatMessage, index: Int): View {
        val roleLabel = if (message.role == "assistant") "ASSISTANT" else "USER"
        val modelLabel = message.modelName.ifBlank { state.currentPreset()?.name.orEmpty() }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            background = solid(color(R.color.chat_subtle_surface))
            addView(TextView(context).apply {
                text = roleLabel
                setTextColor(color(R.color.chat_fg))
                textSize = 12f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = modelLabel.uppercase(Locale.ROOT)
                setTextColor(color(R.color.chat_accent_blue))
                textSize = 10f
                typeface = systemTypeface(Typeface.BOLD)
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
                typeface = systemTypeface(Typeface.BOLD)
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
            background = solid(color(R.color.chat_subtle_surface))
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
            setTextColor(color(R.color.chat_fg))
            setHintTextColor(color(R.color.chat_muted))
            background = rounded(color(R.color.chat_subtle_surface), dp(8), dp(1), color(R.color.chat_border))
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
                color(R.color.chat_card),
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
                    renderMessageLimit = defaultRenderMessageLimit
                    repository.save(state)
                    renderAll()
                    setSidebarVisible(false)
                }
            }

            addView(TextView(context).apply {
                text = session.name
                setTextColor(color(R.color.chat_fg))
                textSize = 14f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                val promptName = state.promptPresets[session.promptPresetName]?.name ?: "默认"
                text = "${session.modelName.ifBlank { state.currentPreset()?.name.orEmpty() }} · 提示词:$promptName · ${session.history.size} 条消息"
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
        settingsCompact = resources.displayMetrics.widthPixels / resources.displayMetrics.density < 720f

        val contentHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val tabs = listOf("模型预设", "提示词", "备份")
        val tabViews = mutableListOf<TextView>()
        var saveCurrentPane: (() -> Boolean)? = null

        val body = LinearLayout(this).apply {
            orientation = if (settingsCompact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            background = rounded(color(R.color.chat_panel), dp(16), dp(1), color(R.color.chat_border))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val tabColumn = LinearLayout(this).apply {
            orientation = if (settingsCompact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            background = solid(color(R.color.chat_subtle_surface))
            layoutParams = if (settingsCompact) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            } else {
                LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        tabs.forEachIndexed { index, label ->
            val tab = TextView(this).apply {
                text = label
                gravity = if (settingsCompact) Gravity.CENTER else Gravity.CENTER_VERTICAL
                setTextColor(if (index == 0) color(R.color.chat_accent_blue) else color(R.color.chat_muted))
                textSize = 13f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
                setPadding(dp(16), 0, dp(10), 0)
                background = if (index == 0) solid(Color.argb(38, 96, 165, 250)) else null
                layoutParams = if (settingsCompact) {
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                } else {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
                }
            }
            tabViews.add(tab)
            tabColumn.addView(tab)
        }

        val paneScroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            layoutParams = if (settingsCompact) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
        }
        paneScroll.addView(contentHost)
        body.addView(tabColumn)
        body.addView(paneScroll)

        val footer = LinearLayout(this).apply {
            gravity = Gravity.END
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = solid(color(R.color.chat_subtle_surface))
        }
        footer.addView(dialogButton("保存").apply {
            setOnClickListener {
                if (saveCurrentPane?.invoke() == false) return@setOnClickListener
                repository.save(state)
                renderAll()
                toast("已保存")
            }
        })
        footer.addView(dialogButton("关闭").apply { setOnClickListener { dialog.dismiss() } })

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color(R.color.chat_panel), dp(16), dp(1), color(R.color.chat_border))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(body)
            addView(footer)
        }

        fun selectTab(index: Int) {
            tabViews.forEachIndexed { i, tab ->
                tab.setTextColor(if (i == index) color(R.color.chat_accent_blue) else color(R.color.chat_muted))
                tab.background = if (i == index) solid(Color.argb(38, 96, 165, 250)) else null
            }
            contentHost.animate().cancel()
            contentHost.removeAllViews()
            saveCurrentPane = when (index) {
                0 -> fillModelsPane(contentHost)
                1 -> fillPromptsPane(contentHost)
                else -> fillBackupPane(contentHost, dialog)
            }
            contentHost.alpha = 0f
            contentHost.translationY = dp(4).toFloat()
            contentHost.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(360)
                .setInterpolator(entranceInterpolator)
                .start()
        }
        tabViews.forEachIndexed { index, tab ->
            tab.setOnClickListener { selectTab(index) }
            installPressAnimation(tab)
        }
        selectTab(0)

        dialog.setContentView(shell)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            val width = min(resources.displayMetrics.widthPixels - dp(24), dp(960))
            val height = min(resources.displayMetrics.heightPixels - dp(72), dp(720))
            dialog.window?.setLayout(width, height)
            shell.alpha = 0f
            shell.scaleX = 0.96f
            shell.scaleY = 0.96f
            shell.translationY = dp(8).toFloat()
            shell.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(480)
                .setInterpolator(entranceInterpolator)
                .start()
        }
        dialog.show()
    }

    private fun fillModelsPane(host: LinearLayout): () -> Boolean {
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
            val backup = state.modelPresets.map { ModelPreset.fromJson(it.toJson()) }
            editors.forEach { it.applyTo(state.modelPresets[it.index]) }
            val enabled = state.enabledPresets()
            if (enabled.size > 2) {
                state.modelPresets.clear()
                state.modelPresets.addAll(backup)
                toast("最多只能启用2个模型预设")
                false
            } else {
                if (enabled.isEmpty()) state.modelPresets.firstOrNull()?.enabled = true
                state.currentModelIndex = state.currentModelIndex.coerceIn(0, max(0, state.enabledPresets().size - 1))
                true
            }
        }
    }

    private fun fillPromptsPane(host: LinearLayout): () -> Boolean {
        if (!state.promptPresets.containsKey("default")) state.promptPresets["default"] = PromptPreset()
        var currentKey = state.activeSession()?.promptPresetName
            ?.takeIf { state.promptPresets.containsKey(it) }
            ?: "default"
        var currentEditor: PromptPresetEditor? = null

        val body = LinearLayout(this).apply {
            orientation = if (settingsCompact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val listHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (settingsCompact) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(14) }
            } else {
                LinearLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(16) }
            }
        }
        val editorHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = if (settingsCompact) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        body.addView(listHost)
        body.addView(editorHost)
        host.addView(body)

        fun saveEditor(): Boolean {
            val editor = currentEditor ?: return true
            val preset = state.promptPresets[editor.key] ?: return true
            editor.applyTo(preset)
            return true
        }

        fun renderList() {
            listHost.removeAllViews()
            listHost.addView(dialogTitle("提示词预设"))
            state.promptPresets.keys.sorted().forEach { key ->
                val preset = state.promptPresets[key] ?: return@forEach
                listHost.addView(promptPresetListItem(key, preset, key == currentKey).apply {
                    setOnClickListener {
                        saveEditor()
                        currentKey = key
                        renderList()
                        currentEditor = renderPromptPresetEditor(editorHost, key)
                    }
                })
            }
            listHost.addView(dialogButton("+ 新增预设").apply {
                setOnClickListener {
                    saveEditor()
                    val key = uniquePromptKey("preset_${state.promptPresets.size + 1}")
                    state.promptPresets[key] = PromptPreset(name = "新预设 ${state.promptPresets.size + 1}")
                    currentKey = key
                    renderList()
                    currentEditor = renderPromptPresetEditor(editorHost, key)
                }
            })
            listHost.addView(dialogButton("复制当前").apply {
                setOnClickListener {
                    saveEditor()
                    val source = state.promptPresets[currentKey] ?: return@setOnClickListener
                    val key = uniquePromptKey("${currentKey}_copy")
                    state.promptPresets[key] = source.copy(name = "${source.name} 副本")
                    currentKey = key
                    renderList()
                    currentEditor = renderPromptPresetEditor(editorHost, key)
                }
            })
            listHost.addView(dialogButton("删除当前").apply {
                setOnClickListener {
                    if (currentKey == "default") {
                        toast("无法删除默认预设")
                        return@setOnClickListener
                    }
                    state.promptPresets.remove(currentKey)
                    state.sessions.values.forEach {
                        if (it.promptPresetName == currentKey) it.promptPresetName = "default"
                    }
                    currentKey = "default"
                    renderList()
                    currentEditor = renderPromptPresetEditor(editorHost, currentKey)
                }
            })
        }

        renderList()
        currentEditor = renderPromptPresetEditor(editorHost, currentKey)
        return { saveEditor() }
    }

    private fun promptPresetListItem(key: String, preset: PromptPreset, selected: Boolean): View {
        val desc = listOf(preset.sysPrompt, preset.firstAssistant, preset.firstUser)
            .firstOrNull { it.isNotBlank() }
            ?.take(30)
            ?: "空"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(
                if (selected) Color.argb(56, 96, 165, 250) else Color.argb(154, 21, 25, 33),
                dp(8),
                dp(1),
                if (selected) color(R.color.chat_accent_blue) else color(R.color.chat_border)
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            addView(TextView(context).apply {
                text = preset.name.ifBlank { key }
                setTextColor(if (selected) color(R.color.chat_accent_blue) else Color.WHITE)
                textSize = 13f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = desc
                setTextColor(color(R.color.chat_muted))
                textSize = 11f
                setPadding(0, dp(6), 0, 0)
                includeFontPadding = false
            })
        }
    }

    private fun renderPromptPresetEditor(host: LinearLayout, key: String): PromptPresetEditor {
        host.removeAllViews()
        val preset = state.promptPresets[key] ?: PromptPreset().also { state.promptPresets[key] = it }
        host.addView(dialogTitle("编辑预设"))
        host.addView(TextView(this).apply {
            text = "当前键: $key"
            setTextColor(color(R.color.chat_muted))
            textSize = 11f
            setPadding(0, 0, 0, dp(12))
        })
        val name = field("预设名称", preset.name)
        val sys = field("系统提示词", preset.sysPrompt, multiLine = true)
        val firstUser = field("首条用户消息", preset.firstUser, multiLine = true)
        val firstAssistant = field("首条助手消息", preset.firstAssistant, multiLine = true)
        val prefix = field("消息前缀", preset.messagePrefix, multiLine = true)
        val prefill = field("助手预填内容", preset.assistantPrefill, multiLine = true)
        listOf(name, sys, firstUser, firstAssistant, prefix, prefill).forEach { host.addView(it.container) }

        host.addView(separator(dp(10)))
        host.addView(sectionLabel("多步任务编排器"))
        val multiStep = field("配置(JSON)", preset.multiStepRunnerJson, multiLine = true)
        host.addView(multiStep.container)

        host.addView(separator(dp(10)))
        host.addView(sectionLabel("正则替换规则(生成后处理)"))
        val regexHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        renderRegexRuleEditors(regexHost, preset.regexRulesJson)
        host.addView(regexHost)
        host.addView(dialogButton("+ 添加规则").apply {
            setOnClickListener { addRegexRuleEditor(regexHost, JSONObject()) }
        })

        return PromptPresetEditor(
            key = key,
            name = name,
            sys = sys,
            firstUser = firstUser,
            firstAssistant = firstAssistant,
            prefix = prefix,
            prefill = prefill,
            multiStep = multiStep,
            regexHost = regexHost,
            regexRules = { collectRegexRules(regexHost).toString() }
        )
    }

    private fun sectionLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            setTextColor(color(R.color.chat_muted))
            textSize = 13f
            typeface = systemTypeface(Typeface.BOLD)
            includeFontPadding = false
            setPadding(0, dp(12), 0, dp(10))
        }
    }

    private fun renderRegexRuleEditors(host: LinearLayout, rulesJson: String) {
        host.removeAllViews()
        val rules = runCatching { JSONArray(rulesJson) }.getOrDefault(JSONArray())
        if (rules.length() == 0) {
            host.addView(TextView(this).apply {
                tag = "empty"
                text = "暂无规则。点击下方按钮添加。"
                setTextColor(color(R.color.chat_muted))
                textSize = 11f
                setPadding(0, dp(6), 0, dp(10))
            })
            return
        }
        for (i in 0 until rules.length()) {
            addRegexRuleEditor(host, rules.optJSONObject(i) ?: JSONObject())
        }
    }

    private fun addRegexRuleEditor(host: LinearLayout, rule: JSONObject) {
        if (host.childCount == 1 && (host.getChildAt(0).tag as? String) == "empty") {
            host.removeAllViews()
        } else if (host.childCount == 1 && host.getChildAt(0) is TextView && host.getChildAt(0).tag == null) {
            host.removeAllViews()
        }
        val name = field("规则名称", rule.optString("name"))
        val pattern = field("查找正则", rule.optString("pattern"), multiLine = true)
        val replacement = field("替换为", rule.optString("replacement"), multiLine = true)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color(R.color.chat_card), dp(10), dp(1), color(R.color.chat_border))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            tag = RegexRuleRefs(name, pattern, replacement)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            addView(name.container)
            addView(pattern.container)
            addView(replacement.container)
        }
        card.addView(dialogButton("删除规则").apply {
            setOnClickListener { host.removeView(card) }
        })
        host.addView(card)
    }

    private fun collectRegexRules(host: LinearLayout): JSONArray {
        val rules = JSONArray()
        for (i in 0 until host.childCount) {
            val refs = host.getChildAt(i).tag as? RegexRuleRefs ?: continue
            val pattern = refs.pattern.value().trim()
            if (pattern.isBlank()) continue
            rules.put(
                JSONObject()
                    .put("name", refs.name.value().trim())
                    .put("pattern", pattern)
                    .put("replacement", refs.replacement.value())
            )
        }
        return rules
    }

    private fun uniquePromptKey(seed: String): String {
        val base = seed.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_\\-]+"), "_")
            .trim('_')
            .ifBlank { "preset" }
        var key = base
        var index = 2
        while (state.promptPresets.containsKey(key)) {
            key = "${base}_$index"
            index += 1
        }
        return key
    }

    private fun fillBackupPane(host: LinearLayout, dialog: Dialog): () -> Boolean {
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
        return { true }
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
            background = rounded(color(R.color.chat_card), dp(12), dp(1), color(R.color.chat_border))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            editor.rows.forEach { addView(it) }
        }
    }

    private fun tinyButton(label: String, danger: Boolean = false): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(if (danger) Color.WHITE else color(R.color.chat_fg))
            textSize = 12f
            typeface = systemTypeface(Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = rounded(
                if (danger) color(R.color.chat_danger) else color(R.color.chat_panel),
                dp(6),
                if (danger) 0 else dp(1),
                color(R.color.chat_border)
            )
            setPadding(dp(10), dp(5), dp(10), dp(5))
            installPressAnimation(this)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
    }

    private fun dialogTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            setTextColor(color(R.color.chat_fg))
            textSize = 16f
            typeface = systemTypeface(Typeface.BOLD)
            includeFontPadding = false
            setPadding(0, 0, 0, dp(18))
        }
    }

    private fun dialogButton(label: String): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(color(R.color.chat_fg))
            textSize = 13f
            typeface = systemTypeface(Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = rounded(color(R.color.chat_panel), dp(8), dp(1), color(R.color.chat_border))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            installPressAnimation(this)
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
            setTextColor(color(R.color.chat_fg))
            setHintTextColor(color(R.color.chat_muted))
            textSize = 14f
            minLines = if (multiLine) 3 else 1
            maxLines = if (multiLine) 8 else 1
            setSingleLine(!multiLine)
            gravity = if (multiLine) Gravity.TOP or Gravity.START else Gravity.CENTER_VERTICAL
            background = rounded(color(R.color.chat_subtle_surface), dp(8), dp(1), color(R.color.chat_border))
            setPadding(dp(12), if (multiLine) dp(10) else 0, dp(12), if (multiLine) dp(10) else 0)
            layoutParams = if (settingsCompact) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (multiLine) dp(120) else dp(44))
            } else {
                LinearLayout.LayoutParams(0, if (multiLine) dp(96) else dp(44), 1f)
            }
        }
        val container = LinearLayout(this).apply {
            orientation = if (settingsCompact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (settingsCompact) Gravity.START else Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, if (settingsCompact) dp(14) else dp(11))
            addView(TextView(context).apply {
                text = label
                setTextColor(color(R.color.chat_muted))
                textSize = 13f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
                layoutParams = if (settingsCompact) {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = dp(8) }
                } else {
                    LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            })
            addView(input)
        }
        return FieldRef(container, input)
    }

    private fun selectField(
        label: String,
        options: List<Pair<String, String>>,
        selected: String
    ): SelectRef {
        val labels = options.map { it.second }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(color(R.color.chat_fg))
                    textSize = 14f
                    includeFontPadding = false
                }
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (super.getDropDownView(position, convertView, parent) as TextView).apply {
                    setTextColor(color(R.color.chat_fg))
                    setBackgroundColor(color(R.color.chat_panel))
                    textSize = 14f
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val spinner = Spinner(this).apply {
            this.adapter = adapter
            setSelection(options.indexOfFirst { it.first == selected }.takeIf { it >= 0 } ?: 0)
            background = rounded(color(R.color.chat_subtle_surface), dp(8), dp(1), color(R.color.chat_border))
            layoutParams = if (settingsCompact) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
            } else {
                LinearLayout.LayoutParams(0, dp(44), 1f)
            }
        }
        val container = LinearLayout(this).apply {
            orientation = if (settingsCompact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (settingsCompact) Gravity.START else Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, if (settingsCompact) dp(14) else dp(11))
            addView(TextView(context).apply {
                text = label
                setTextColor(color(R.color.chat_muted))
                textSize = 13f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
                layoutParams = if (settingsCompact) {
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { bottomMargin = dp(8) }
                } else {
                    LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            })
            addView(spinner)
        }
        return SelectRef(container, spinner, options)
    }

    private fun yesNoOptions(): List<Pair<String, String>> {
        return listOf("true" to "是", "false" to "否")
    }

    private fun modelTypeOptions(): List<Pair<String, String>> {
        return listOf(
            "openai" to "OpenAI",
            "anthropic" to "Anthropic兼容",
            "deepseek" to "DeepSeek直连",
            "kimi" to "Kimi",
            "gemini" to "Gemini直连",
            "gemini-proxy" to "Gemini代理"
        )
    }

    private fun providerDefaultBase(type: String, value: String): String {
        if (value.isNotBlank()) return value
        return when (type) {
            "anthropic" -> "https://api.anthropic.com/v1"
            "deepseek" -> "https://api.deepseek.com"
            "kimi" -> "https://api.moonshot.cn/v1"
            "gemini" -> "https://generativelanguage.googleapis.com"
            else -> "https://api.openai.com/v1"
        }
    }

    private fun providerDefaultModel(type: String, value: String): String {
        if (value.isNotBlank()) return value
        return when (type) {
            "anthropic" -> "claude-3-5-sonnet-20240620"
            "deepseek" -> "deepseek-v4-flash"
            "kimi" -> "kimi-k2.5"
            "gemini", "gemini-proxy" -> "gemini-2.5-pro"
            else -> "gpt-4o-mini"
        }
    }

    private fun effortOptions(type: String): List<Pair<String, String>> {
        return when (type) {
            "deepseek" -> listOf(
                "" to "默认(high)",
                "__omit__" to "不发送(兼容)",
                "high" to "high",
                "max" to "max"
            )
            "anthropic" -> listOf(
                "" to "默认(high)",
                "__omit__" to "不发送(兼容)",
                "low" to "low",
                "medium" to "medium",
                "high" to "high",
                "xhigh" to "xhigh",
                "max" to "max"
            )
            else -> listOf(
                "" to "默认(medium)",
                "__omit__" to "不发送(兼容)",
                "none" to "none",
                "minimal" to "minimal",
                "low" to "low",
                "medium" to "medium",
                "high" to "high",
                "xhigh" to "xhigh"
            )
        }
    }

    private fun geminiThinkingLevelOptions(): List<Pair<String, String>> {
        return listOf(
            "" to "默认(high/自动)",
            "minimal" to "minimal",
            "low" to "low",
            "medium" to "medium",
            "high" to "high"
        )
    }

    private fun modelPresetEditor(index: Int, preset: ModelPreset): ModelPresetEditor {
        val typeValue = preset.type.lowercase(Locale.ROOT).ifBlank { "openai" }
        val enabled = selectField("启用", yesNoOptions(), if (preset.enabled) "true" else "false")
        val name = field("显示名称", preset.name)
        val type = selectField("模型类型", modelTypeOptions(), typeValue)
        val base = if (typeValue == "gemini-proxy") null else field("Base URL", providerDefaultBase(typeValue, preset.config.base))
        val proxyUrl = if (typeValue == "gemini-proxy") field("代理URL", preset.config.proxyUrl.ifBlank { "http://127.0.0.1:8889" }) else null
        val proxyPass = if (typeValue == "gemini-proxy") field("代理密码", preset.config.proxyPass) else null
        val key = if (typeValue == "gemini-proxy") null else field("API Key", preset.config.key)
        val model = field("模型名", providerDefaultModel(typeValue, preset.config.model))
        val temperature = field("温度(0=不发送)", preset.config.temperature.toString())
        val topP = field("Top P(0=不发送)", preset.config.topP.toString())
        val topK = if (typeValue == "gemini" || typeValue == "gemini-proxy") field("Top K(0=不发送)", preset.config.topK.takeIf { it != 0 }?.toString().orEmpty()) else null
        val tokenValue = if (typeValue == "gemini" || typeValue == "gemini-proxy") {
            preset.config.maxOutputTokens
        } else {
            preset.config.maxTokens
        }
        val maxTokens = field("最大输出长度(0=不发送)", tokenValue.toString())
        val stream = selectField("流式输出", yesNoOptions(), if (preset.config.stream) "true" else "false")
        val useThinking = selectField("启用思维链", yesNoOptions(), if (preset.config.useThinking) "true" else "false")
        val thinkingEffort = when (typeValue) {
            "gemini", "gemini-proxy", "kimi" -> null
            else -> selectField("思维链级别", effortOptions(typeValue), preset.config.thinkingEffort)
        }
        val thinkingLevel = if (typeValue == "gemini" || typeValue == "gemini-proxy") {
            selectField("思维级别", geminiThinkingLevelOptions(), preset.config.thinkingLevel)
        } else {
            null
        }
        val thinkingBudget = when (typeValue) {
            "anthropic" -> field("思维预算", preset.config.thinkingBudget.takeIf { it >= 1024 }?.toString().orEmpty())
            "gemini", "gemini-proxy" -> field("思维预算", preset.config.thinkingBudget.toString())
            else -> null
        }
        val rows = mutableListOf<View>(
            enabled.container,
            name.container,
            type.container
        )
        listOf(base, proxyUrl, proxyPass, key, model, temperature, topP, topK, maxTokens).forEach {
            if (it != null) rows.add(it.container)
        }
        rows.add(stream.container)
        rows.add(useThinking.container)
        if (thinkingEffort != null) rows.add(thinkingEffort.container)
        if (thinkingLevel != null) rows.add(thinkingLevel.container)
        if (thinkingBudget != null) rows.add(thinkingBudget.container)

        return ModelPresetEditor(
            index = index,
            rows = rows,
            enabled = enabled,
            name = name,
            type = type,
            base = base,
            proxyUrl = proxyUrl,
            proxyPass = proxyPass,
            model = model,
            key = key,
            temperature = temperature,
            topP = topP,
            topK = topK,
            maxTokens = maxTokens,
            stream = stream,
            useThinking = useThinking,
            thinkingEffort = thinkingEffort,
            thinkingBudget = thinkingBudget,
            thinkingLevel = thinkingLevel
        )
    }

    private fun applySystemFontTree(view: View) {
        if (view is TextView) {
            view.typeface = systemTypeface(view.typeface?.style ?: Typeface.NORMAL)
            if (view.isClickable) installPressAnimation(view)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applySystemFontTree(view.getChildAt(i))
            }
        }
    }

    private fun systemTypeface(style: Int = Typeface.NORMAL): Typeface {
        val wantsBold = style and Typeface.BOLD != 0
        val wantsItalic = style and Typeface.ITALIC != 0
        return when {
            wantsBold && wantsItalic -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            wantsBold -> Typeface.DEFAULT_BOLD
            wantsItalic -> Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            else -> Typeface.DEFAULT
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installPressAnimation(view: View) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(0.962f)
                        .scaleY(0.962f)
                        .translationY(dp(1).toFloat())
                        .alpha(0.9f)
                        .setDuration(120)
                        .setInterpolator(softInterpolator)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(360)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                        .start()
                }
            }
            false
        }
    }

    private fun separator(height: Int = dp(1)): View {
        return View(this).apply {
            setBackgroundColor(color(R.color.chat_separator))
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

    private data class SelectRef(
        val container: View,
        val spinner: Spinner,
        val options: List<Pair<String, String>>
    ) {
        fun value(): String = options.getOrNull(spinner.selectedItemPosition)?.first.orEmpty()
    }

    private data class RegexRuleRefs(
        val name: FieldRef,
        val pattern: FieldRef,
        val replacement: FieldRef
    )

    private data class StreamRenderTarget(
        var message: ChatMessage,
        var targetContent: String = "",
        var targetThinking: String = "",
        var visibleContent: String = "",
        var visibleThinking: String = "",
        var visibleContentLength: Int = visibleContent.length,
        var visibleThinkingLength: Int = visibleThinking.length,
        var finishWhenCaught: Boolean = false,
        var lastRevealAt: Long = 0L,
        var lastTargetUpdateAt: Long = 0L,
        var lastTickAt: Long = 0L,
        var contentReleaseEnd: Int = 0,
        var unreleasedContentSince: Long = 0L,
        var contentRevealCarry: Float = 0f,
        var thinkingRevealCarry: Float = 0f,
        var lastUiCommitAt: Long = 0L,
        var contentPrefixValid: Boolean = true,
        var thinkingPrefixValid: Boolean = true,
        var releaseScanStart: Int = -1,
        var releaseScanLength: Int = -1,
        var releaseScanBoundary: Int = -1
    )

    private data class RevealBudget(
        val chars: Int,
        val carry: Float
    )

    private data class StreamRenderResult(
        val text: CharSequence,
        val changedStart: Int,
        val changedEnd: Int
    )

    private data class StreamBodyMeasureState(
        var width: Int = 0,
        var textLength: Int = 0,
        var estimatedLines: Int = 1,
        var lineUnits: Float = 0f,
        var unitsPerLine: Float = 1f,
        var lineHeight: Int = 0,
        var baseHeight: Int = 0,
        var targetHeight: Int = 0
    )

    private enum class StreamMode {
        PLAIN,
        DOUBLE_QUOTE,
        CHINESE_QUOTE,
        STAR
    }

    private class StreamFormatState(
        @ColorInt private val quoteColor: Int,
        @ColorInt private val starColor: Int
    ) {
        private val rendered = SpannableStringBuilder()
        private var raw = ""
        private var mode = StreamMode.PLAIN
        private var specialStart = -1

        fun render(text: String): StreamRenderResult {
            val previousRenderedLength = rendered.length
            var appendOnly = true
            if (!text.startsWith(raw)) {
                reset()
                appendOnly = false
            }
            val changedStart = feed(text.substring(raw.length), if (appendOnly) previousRenderedLength else 0)
            raw = text
            return StreamRenderResult(rendered, changedStart.coerceIn(0, rendered.length), rendered.length)
        }

        private fun reset() {
            rendered.clear()
            raw = ""
            mode = StreamMode.PLAIN
            specialStart = -1
        }

        private fun feed(delta: String, initialChangedStart: Int): Int {
            var changedStart = initialChangedStart
            delta.forEach { ch ->
                when {
                    mode == StreamMode.PLAIN && isDoubleQuote(ch) -> {
                        changedStart = min(changedStart, rendered.length)
                        open(StreamMode.DOUBLE_QUOTE, ch)
                    }
                    mode == StreamMode.DOUBLE_QUOTE && isQuote(ch) -> closeQuote(ch)
                    mode == StreamMode.PLAIN && isChineseOpenQuote(ch) -> {
                        changedStart = min(changedStart, rendered.length)
                        open(StreamMode.CHINESE_QUOTE, ch)
                    }
                    mode == StreamMode.CHINESE_QUOTE && (isChineseCloseQuote(ch) || isQuote(ch)) -> closeQuote(ch)
                    mode == StreamMode.PLAIN && isStar(ch) -> {
                        changedStart = min(changedStart, rendered.length)
                        open(StreamMode.STAR, ch)
                    }
                    mode == StreamMode.STAR && isStar(ch) -> closeStar()
                    else -> {
                        changedStart = min(changedStart, rendered.length)
                        rendered.append(ch)
                    }
                }
            }
            return changedStart
        }

        private fun open(nextMode: StreamMode, ch: Char) {
            mode = nextMode
            specialStart = rendered.length
            rendered.append(ch)
        }

        private fun closeQuote(closer: Char) {
            val start = specialStart.coerceAtLeast(0)
            rendered.append(closer)
            rendered.setSpan(ForegroundColorSpan(quoteColor), start, rendered.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            mode = StreamMode.PLAIN
            specialStart = -1
        }

        private fun closeStar() {
            val start = specialStart.coerceAtLeast(0)
            if (start < rendered.length) rendered.delete(start, start + 1)
            if (start < rendered.length) {
                rendered.setSpan(ForegroundColorSpan(starColor), start, rendered.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            mode = StreamMode.PLAIN
            specialStart = -1
        }

        private fun isDoubleQuote(ch: Char): Boolean {
            return ch == '"' || ch == '\uFF02'
        }

        private fun isChineseOpenQuote(ch: Char): Boolean = ch == '\u201C'

        private fun isChineseCloseQuote(ch: Char): Boolean = ch == '\u201D'

        private fun isQuote(ch: Char): Boolean {
            return isDoubleQuote(ch) || isChineseOpenQuote(ch) || isChineseCloseQuote(ch)
        }

        private fun isStar(ch: Char): Boolean {
            return ch == '*' || ch == '\uFF0A'
        }
    }

    private data class PromptPresetEditor(
        val key: String,
        val name: FieldRef,
        val sys: FieldRef,
        val firstUser: FieldRef,
        val firstAssistant: FieldRef,
        val prefix: FieldRef,
        val prefill: FieldRef,
        val multiStep: FieldRef,
        val regexHost: LinearLayout,
        val regexRules: () -> String
    ) {
        fun applyTo(preset: PromptPreset) {
            preset.name = name.value().ifBlank { preset.name }
            preset.sysPrompt = sys.value()
            preset.firstUser = firstUser.value()
            preset.firstAssistant = firstAssistant.value()
            preset.messagePrefix = prefix.value()
            preset.assistantPrefill = prefill.value()
            preset.multiStepRunnerJson = multiStep.value().ifBlank { "{\"enabled\":false,\"steps\":[]}" }
            preset.regexRulesJson = regexRules()
        }
    }

    private class ModelPresetEditor(
        val index: Int,
        val rows: List<View>,
        val enabled: SelectRef,
        val name: FieldRef,
        val type: SelectRef,
        val base: FieldRef?,
        val proxyUrl: FieldRef?,
        val proxyPass: FieldRef?,
        val model: FieldRef,
        val key: FieldRef?,
        val temperature: FieldRef,
        val topP: FieldRef,
        val topK: FieldRef?,
        val maxTokens: FieldRef,
        val stream: SelectRef,
        val useThinking: SelectRef,
        val thinkingEffort: SelectRef?,
        val thinkingBudget: FieldRef?,
        val thinkingLevel: SelectRef?
    ) {
        fun applyTo(preset: ModelPreset) {
            preset.enabled = enabled.value() == "true"
            preset.name = name.value().ifBlank { preset.name }
            preset.type = type.value().trim().lowercase(Locale.ROOT).ifBlank { "openai" }
            base?.let { preset.config.base = it.value().trim() }
            proxyUrl?.let { preset.config.proxyUrl = it.value().trim() }
            proxyPass?.let { preset.config.proxyPass = it.value().trim() }
            preset.config.model = model.value().trim()
            key?.let { preset.config.key = it.value().trim() }
            preset.config.temperature = temperature.value().toDoubleOrNull() ?: preset.config.temperature
            preset.config.topP = topP.value().toDoubleOrNull() ?: preset.config.topP
            topK?.let { preset.config.topK = it.value().toIntOrNull() ?: 0 }
            val maxValue = maxTokens.value().toIntOrNull()
            if (preset.type == "gemini" || preset.type == "gemini-proxy") {
                if (maxValue != null) preset.config.maxOutputTokens = maxValue
            } else {
                if (maxValue != null) {
                    preset.config.maxTokens = maxValue
                    preset.config.maxOutputTokens = maxValue
                }
            }
            preset.config.stream = stream.value() == "true"
            preset.config.useThinking = useThinking.value() == "true"
            thinkingEffort?.let { preset.config.thinkingEffort = it.value() }
            thinkingBudget?.let { preset.config.thinkingBudget = it.value().toIntOrNull() ?: preset.config.thinkingBudget }
            thinkingLevel?.let { preset.config.thinkingLevel = it.value() }
        }
    }
}
