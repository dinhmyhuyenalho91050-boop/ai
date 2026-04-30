package com.example.htmlapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RecordingCanvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.Process
import android.os.SystemClock
import android.os.WorkDuration
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextWatcher
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.graphics.text.LineBreakConfig
import android.graphics.text.LineBreaker
import android.graphics.text.MeasuredText
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var root: FrameLayout
    private lateinit var messagesScroll: RecyclerView
    private lateinit var messagesLayoutManager: LinearLayoutManager
    private lateinit var messagesAdapter: MessageAdapter
    private lateinit var inputMessage: EditText
    private lateinit var sendButton: TextView
    private lateinit var composer: View
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
    private val messageStreamBodyViews = mutableMapOf<String, StreamBodyViews>()
    private val messageThinkingViews = mutableMapOf<String, TextView>()
    private val messageThinkingContainers = mutableMapOf<String, View>()
    private val messageThinkingSummaries = mutableMapOf<String, TextView>()
    private val messageRenderedContent = mutableMapOf<String, String>()
    private val messageRenderedThinking = mutableMapOf<String, String>()
    private val messageStreamSegments = mutableMapOf<String, StreamTextSegmentState>()
    private val streamBodyHeightAnimationTokens = mutableMapOf<String, Int>()
    private val streamBodyHeightAnimators = mutableMapOf<String, ValueAnimator>()
    private val streamBodyHeightTargets = mutableMapOf<String, Int>()
    private val streamBodyMeasureStates = mutableMapOf<String, StreamBodyMeasureState>()
    private val streamTargets = mutableMapOf<String, StreamRenderTarget>()
    private val messageThinkingExpanded = mutableSetOf<String>()
    private val stoppedAssistantIds = mutableSetOf<String>()
    private var activeAssistantId: String? = null
    private var isSending = false
    private var pendingImportReplace = false
    @Volatile private var followBottom = true
    private var bottomScrollScheduled = false
    private var scrollTrackScheduled = false
    private var streamAnimatorScheduled = false
    @Volatile private var streamLiveModeNow = false
    @Volatile private var streamThrottledModeNow = false
    private var userTouchingMessages = false
    private var messageTouchToken = 0
    private var programmaticScroll = false
    private var programmaticScrollReleaseScheduled = false
    private var programmaticScrollReleaseAt = 0L
    private var lastBottomTargetY = -1
    private var lastStreamBottomScrollAt = 0L
    private var settingsCompact = false
    private var streamHeightAnimationSeed = 0
    private var editingMessageId: String? = null
    private var editingMessageDraft: String = ""
    private var editingMessageEditor: EditText? = null
    private var editingFloatingButton: TextView? = null
    private var baseMessagesBottomPadding = 0
    private var editScrollRestoreToken = 0
    private var sendButtonVisualSending = false
    private var sendButtonAnimationToken = 0
    private val compositeEffectTokens = WeakHashMap<View, Int>()
    private var compositeEffectTokenSeed = 0
    private val recentlyInsertedMessageIds = mutableSetOf<String>()
    private val editTransitionMessageIds = mutableSetOf<String>()
    private val editExitLockedHeights = mutableMapOf<String, Int>()
    private val nonStreamingAssistantIds = mutableSetOf<String>()
    private val messageHeightCache = LruCacheMap<MessageHeightKey, Int>(260)
    private val formattedTextCache = LruCacheMap<TextFormatCacheKey, CharSequence>(180)
    private var displayPerformanceMode: DisplayPerformanceMode? = null
    private var currentRequestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
    private var performanceHintManager: PerformanceHintManager? = null
    private var uiPerformanceHintSession: PerformanceHintManager.Session? = null
    private var uiPerformanceHintTargetNs = 0L
    private var uiPerformanceHintPowerEfficient: Boolean? = null
    private var streamBottomFollowToken = 0

    private val defaultRenderMessageLimit = 80
    private val loadOlderMessageBatch = 40
    private var renderMessageLimit = defaultRenderMessageLimit
    private val scrollTrackFrameMs = 96L
    private val streamOffscreenUiFrameMs = 1000L
    private val streamOffscreenFlushChars = 360
    private val streamThrottledUiFrameMs = 900L
    private val streamLiveDistanceDp = 240
    private val streamThrottledDistanceDp = 2400
    private val streamLineFlushChars = 600
    private val streamThinkingFlushChars = 480
    private val streamLiveFlushMs = 48L
    private val streamFastFlushMs = 900L
    private val streamRevealFrameMs = 48L
    private val streamRevealTouchFrameMs = 120L
    private val streamUiCommitFrameMs = 48L
    private val streamContentMaxCharsPerTick = 2048
    private val streamCollapsedThinkingFrameMs = 500L
    private val streamExpandedThinkingFrameMs = 120L
    private val streamThinkingCharsPerSecond = 240f
    private val streamMaxThinkingCharsPerFrame = 64
    private val streamTailTargetChars = 720
    private val streamTailMaxChars = 1200
    private val streamFrozenPrefixMinChars = 700
    private val streamFreezeScanStepChars = 160
    private val streamBottomScrollFrameMs = 16L
    private var streamBottomFollowScheduled = false
    private val scrollTouchCooldownMs = 280L
    private val softInterpolator by lazy { PathInterpolator(0.2f, 0f, 0f, 1f) }
    private val entranceInterpolator by lazy { PathInterpolator(0.16f, 1f, 0.3f, 1f) }
    private val streamHeightInterpolator by lazy { PathInterpolator(0.2f, 0f, 0.2f, 1f) }
    private val streamLineBreakConfig: LineBreakConfig by lazy {
        LineBreakConfig.Builder()
            .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_NONE)
            .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
            .setHyphenation(LineBreakConfig.HYPHENATION_DISABLED)
            .build()
    }
    private val compositeRevealShaderSource = """
        uniform shader input;
        uniform float progress;
        uniform float2 size;
        half4 main(float2 p) {
            half4 c = input.eval(p);
            float y = clamp(p.y / max(size.y, 1.0), 0.0, 1.0);
            float reveal = smoothstep(0.0, 1.0, progress + (1.0 - y) * 0.08);
            float lift = 0.985 + reveal * 0.015;
            return half4(c.rgb * lift, c.a);
        }
    """.trimIndent()

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
        setupDevicePerformanceControls()
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
        uiPerformanceHintSession?.close()
        uiPerformanceHintSession = null
        super.onDestroy()
    }

    @SuppressLint("NewApi")
    private fun setupDevicePerformanceControls() {
        val attrs = window.attributes
        attrs.setFrameRatePowerSavingsBalanced(true)
        attrs.setFrameRateBoostOnTouchEnabled(false)
        window.attributes = attrs
        performanceHintManager = getSystemService(PerformanceHintManager::class.java)
        uiPerformanceHintSession = runCatching {
            performanceHintManager
                ?.createHintSession(intArrayOf(Process.myTid()), displayPerformanceTargetNs(DisplayPerformanceMode.IDLE))
        }.getOrNull()
        applyDisplayPerformanceMode(DisplayPerformanceMode.IDLE, force = true)
    }

    @SuppressLint("NewApi")
    private fun refreshDisplayPerformanceMode() {
        if (!::root.isInitialized || !::messagesScroll.isInitialized) return
        val mode = when {
            streamTargets.isNotEmpty() && streamLiveModeNow -> DisplayPerformanceMode.STREAM_LIVE
            userTouchingMessages -> DisplayPerformanceMode.TOUCH_SCROLL
            streamTargets.isNotEmpty() -> DisplayPerformanceMode.STREAM_BACKGROUND
            else -> DisplayPerformanceMode.IDLE
        }
        applyDisplayPerformanceMode(mode)
    }

    @SuppressLint("NewApi")
    private fun applyDisplayPerformanceMode(mode: DisplayPerformanceMode, force: Boolean = false) {
        if (!force && displayPerformanceMode == mode) return
        displayPerformanceMode = mode
        val frameRate = displayPerformanceFrameRate(mode)
        currentRequestedFrameRate = frameRate
        root.propagateRequestedFrameRate(frameRate, true)
        messagesScroll.propagateRequestedFrameRate(frameRate, true)
        composer.setRequestedFrameRate(frameRate)
        updateUiPerformanceHintTarget(mode)
    }

    @SuppressLint("NewApi")
    private fun updateUiPerformanceHintTarget(mode: DisplayPerformanceMode) {
        val session = uiPerformanceHintSession ?: return
        val targetNs = displayPerformanceTargetNs(mode)
        if (targetNs != uiPerformanceHintTargetNs) {
            uiPerformanceHintTargetNs = targetNs
            runCatching { session.updateTargetWorkDuration(targetNs) }
        }
        val preferPower = mode != DisplayPerformanceMode.STREAM_LIVE
        if (uiPerformanceHintPowerEfficient != preferPower) {
            uiPerformanceHintPowerEfficient = preferPower
            runCatching { session.setPreferPowerEfficiency(preferPower) }
        }
    }

    private fun displayPerformanceFrameRate(mode: DisplayPerformanceMode): Float {
        return when (mode) {
            DisplayPerformanceMode.IDLE -> View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
            DisplayPerformanceMode.STREAM_BACKGROUND -> View.REQUESTED_FRAME_RATE_CATEGORY_LOW
            DisplayPerformanceMode.TOUCH_SCROLL -> View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL
            DisplayPerformanceMode.STREAM_LIVE -> View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    private fun displayPerformanceTargetNs(mode: DisplayPerformanceMode): Long {
        return when (mode) {
            DisplayPerformanceMode.STREAM_LIVE -> 8_333_333L
            DisplayPerformanceMode.TOUCH_SCROLL -> 16_666_667L
            DisplayPerformanceMode.STREAM_BACKGROUND -> 33_333_333L
            DisplayPerformanceMode.IDLE -> 33_333_333L
        }
    }

    private fun beginUiWorkHint(): Long {
        return if (uiPerformanceHintSession == null) 0L else SystemClock.uptimeNanos()
    }

    @SuppressLint("NewApi")
    private fun finishUiWorkHint(startNs: Long) {
        val session = uiPerformanceHintSession ?: return
        if (startNs <= 0L) return
        val elapsedNs = (SystemClock.uptimeNanos() - startNs).coerceAtLeast(1L)
        val duration = WorkDuration().apply {
            setWorkPeriodStartTimestampNanos(startNs)
            setActualTotalDurationNanos(elapsedNs)
            setActualCpuDurationNanos(elapsedNs)
            setActualGpuDurationNanos(0L)
        }
        runCatching { session.reportActualWorkDuration(duration) }
    }

    @SuppressLint("NewApi")
    private fun beginBackgroundWorkHint(targetNs: Long): PerformanceWorkSession? {
        val manager = performanceHintManager ?: return null
        val startNs = SystemClock.uptimeNanos()
        val session = runCatching {
            manager.createHintSession(intArrayOf(Process.myTid()), targetNs)?.apply {
                setPreferPowerEfficiency(true)
            }
        }.getOrNull() ?: return null
        return PerformanceWorkSession(session, startNs)
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
                    editScrollRestoreToken++
                    userTouchingMessages = true
                    detachBottomFollowForTouch()
                    updateStreamDistanceModes()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (userTouchingMessages) followBottom = false
                    updateStreamDistanceModes()
                    scheduleScrollTracking()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val token = ++messageTouchToken
                    followBottom = isNearBottom()
                    updateStreamDistanceModes()
                    mainHandler.postDelayed({
                        if (token == messageTouchToken) {
                            userTouchingMessages = false
                            if (followBottom || isNearBottom()) {
                                followBottom = true
                                streamLiveModeNow = true
                                streamThrottledModeNow = true
                                scrollToBottomSoon()
                            }
                            scheduleVisibleStreamRefresh()
                        }
                    }, scrollTouchCooldownMs)
                }
            }
            false
        }
        messagesScroll.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                userTouchingMessages = newState == RecyclerView.SCROLL_STATE_DRAGGING ||
                    newState == RecyclerView.SCROLL_STATE_SETTLING
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val token = ++messageTouchToken
                    followBottom = isNearBottom()
                    updateStreamDistanceModes()
                    scheduleVisibleStreamRefresh()
                    mainHandler.postDelayed({
                        if (token == messageTouchToken) {
                            userTouchingMessages = false
                            if (followBottom || isNearBottom()) {
                                followBottom = true
                                streamLiveModeNow = true
                                streamThrottledModeNow = true
                                scrollToBottomSoon()
                            }
                            scheduleVisibleStreamRefresh()
                        }
                    }, scrollTouchCooldownMs)
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (userTouchingMessages) followBottom = false
                updateStreamDistanceModes()
                scheduleVisibleStreamRefresh()
                scheduleScrollTracking()
            }
        })
        messagesScroll.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
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
            if (!programmaticScroll && userTouchingMessages) {
                followBottom = isNearBottom()
                updateStreamDistanceModes()
            }
        }, scrollTrackFrameMs)
    }

    private fun scheduleVisibleStreamRefresh() {
        if (streamTargets.isNotEmpty() && shouldUpdateBoundStreamTarget()) scheduleStreamAnimator()
    }

    private fun bindViews() {
        root = findViewById(R.id.root_container)
        messagesScroll = findViewById(R.id.messages_scroll)
        messagesLayoutManager = LinearLayoutManager(this).apply {
            orientation = RecyclerView.VERTICAL
            stackFromEnd = false
        }
        messagesAdapter = MessageAdapter()
        messagesScroll.layoutManager = messagesLayoutManager
        messagesScroll.adapter = messagesAdapter
        messagesScroll.itemAnimator = null
        messagesScroll.setHasFixedSize(false)
        messagesScroll.setItemViewCacheSize(14)
        messagesScroll.recycledViewPool.setMaxRecycledViews(2, 18)
        inputMessage = findViewById(R.id.input_message)
        sendButton = findViewById(R.id.btn_send)
        composer = findViewById(R.id.composer)
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
        baseMessagesBottomPadding = messagesScroll.paddingBottom
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
            if (isSending) {
                stopSending()
            } else {
                submitMessage()
            }
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
            if (editingMessageId != null) {
                finishEditingMessage()
            } else if (sidebar.visibility == View.VISIBLE) {
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
            animateCompositeLayer(sidebar, 480L)
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
                    followBottom = true
                    requestAssistant()
                }
                "assistant" -> {
                    followBottom = true
                    requestAssistant(continueMessage = last)
                }
            }
            return
        }
        val userMessage = ChatMessage(role = "user", content = text, modelName = preset?.name.orEmpty())
        session.history.add(userMessage)
        recentlyInsertedMessageIds.add(userMessage.id)
        followBottom = true
        inputMessage.setText("")
        repository.save(state)
        renderSessions()
        requestAssistant()
    }

    private fun requestAssistant(continueMessage: ChatMessage? = null) {
        val session = state.activeSession() ?: return
        val preset = state.currentPreset()
        val assistant = continueMessage ?: ChatMessage(role = "assistant", content = "", modelName = preset?.name.orEmpty()).also {
            session.history.add(it)
            recentlyInsertedMessageIds.add(it.id)
        }
        val continuationPrefill = continueMessage?.content.orEmpty()
        val promptForRegex = state.promptFor(session).copy()
        assistant.modelName = preset?.name.orEmpty()
        if (preset?.config?.stream == true) {
            nonStreamingAssistantIds.remove(assistant.id)
        } else {
            nonStreamingAssistantIds.add(assistant.id)
        }
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
            val requestHint = beginBackgroundWorkHint(50_000_000L)
            try {
                runCatching {
                aiClient.send(state, session, continuationPrefill.takeIf { it.isNotBlank() }) { content, thinking ->
                    latestContent = content
                    latestThinking = thinking
                    latestDisplayContent = mergeContinuationContent(continuationPrefill, content)
                    val now = android.os.SystemClock.uptimeMillis()
                    val contentDelta = (latestDisplayContent.length - renderedContentLength).coerceAtLeast(0)
                    val thinkingDelta = (latestThinking.length - renderedThinkingLength).coerceAtLeast(0)
                    val contentStart = renderedContentLength.coerceIn(0, latestDisplayContent.length)
                    val thinkingStart = renderedThinkingLength.coerceIn(0, latestThinking.length)
                    val hasLineBreak = latestDisplayContent.indexOf('\n', contentStart) >= 0 ||
                        latestThinking.indexOf('\n', thinkingStart) >= 0
                    val hasLineChunk = contentDelta >= streamLineFlushChars ||
                        thinkingDelta >= streamThinkingFlushChars
                    val liveDisplay = streamLiveModeNow || followBottom
                    val flushMs = if (liveDisplay) streamLiveFlushMs else streamFastFlushMs
                    val waitedLongEnough = now - lastFrameAt >= flushMs
                    val boundaryFlush = liveDisplay && hasLineBreak
                    if ((boundaryFlush || hasLineChunk || waitedLongEnough) &&
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
                    nonStreamingAssistantIds.remove(assistant.id)
                    assistant.content = finalContent
                    assistant.thinking = result.thinking
                    repository.save(state)
                    setSendingUi(false)
                    if (preset?.config?.stream == true || streamTargets.containsKey(assistant.id)) {
                        updateStreamTarget(assistant, assistant.content, assistant.thinking, finishWhenCaught = true)
                    } else {
                        updateMessageViews(
                            assistant,
                            final = true,
                            revealFromTopWhenStuck = continueMessage == null
                        )
                    }
                    renderSessions()
                }
            }.onFailure { error ->
                mainHandler.post {
                    val stopped = stoppedAssistantIds.remove(assistant.id)
                    if (activeAssistantId == assistant.id) activeAssistantId = null
                    nonStreamingAssistantIds.remove(assistant.id)
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
            } finally {
                requestHint?.finish()
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
        val visualChanged = sendButtonVisualSending != sending
        isSending = sending
        sendButtonVisualSending = sending
        if (visualChanged) {
            animateSendButtonState(sending)
        } else {
            applySendButtonVisual(sending)
            if (!sending) settleSendButton()
        }
    }

    private fun applySendButtonVisual(sending: Boolean) {
        sendButton.text = if (sending) "停止" else getString(R.string.action_send)
        sendButton.background = if (sending) {
            roundedGradient(color(R.color.chat_danger), Color.rgb(220, 38, 38), dp(8))
        } else {
            roundedGradient(color(R.color.chat_accent_blue), Color.rgb(59, 130, 246), dp(8))
        }
    }

    private fun animateSendButtonState(sending: Boolean) {
        val token = ++sendButtonAnimationToken
        sendButton.animate().cancel()
        animateCompositeLayer(sendButton, 360L)
        sendButton.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        sendButton.pivotX = sendButton.width / 2f
        sendButton.pivotY = sendButton.height / 2f
        sendButton.animate()
            .scaleX(0.94f)
            .scaleY(0.94f)
            .translationY(dp(2).toFloat())
            .alpha(0.84f)
            .setDuration(100)
            .setInterpolator(softInterpolator)
            .withEndAction {
                if (token == sendButtonAnimationToken) {
                    applySendButtonVisual(sending)
                    sendButton.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(260)
                        .setInterpolator(entranceInterpolator)
                        .withEndAction {
                            if (token == sendButtonAnimationToken) {
                                sendButton.setLayerType(View.LAYER_TYPE_NONE, null)
                            }
                        }
                        .start()
                }
            }
            .start()
    }

    private fun settleSendButton() {
        sendButton.animate().cancel()
        sendButton.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(entranceInterpolator)
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
        streamBodyMeasureStates.clear()
        messageStreamBodyViews.clear()
        messageThinkingViews.clear()
        messageThinkingContainers.clear()
        messageThinkingSummaries.clear()
        messageRenderedContent.clear()
        messageRenderedThinking.clear()
        messageStreamSegments.clear()
        editExitLockedHeights.clear()
        streamLiveModeNow = false
        streamThrottledModeNow = false
        val session = state.ensureSession()
        messagesAdapter.submit(session.history, startIndex = 0, hidden = 0)
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

    private fun clearMessageViewBindings(messageId: String) {
        if (editingMessageId == messageId) {
            editingMessageEditor?.let { editingMessageDraft = it.text.toString() }
            editingMessageEditor = null
        }
        streamBodyHeightAnimators.remove(messageId)?.cancel()
        streamBodyHeightAnimationTokens.remove(messageId)
        streamBodyHeightTargets.remove(messageId)
        streamBodyMeasureStates.remove(messageId)
        messageStreamBodyViews.remove(messageId)
        messageThinkingViews.remove(messageId)
        messageThinkingContainers.remove(messageId)
        messageThinkingSummaries.remove(messageId)
        messageRenderedContent.remove(messageId)
        messageRenderedThinking.remove(messageId)
    }

    private fun stableMessageItemId(id: String): Long {
        var hash = -3750763034362895579L
        id.forEach { ch ->
            hash = hash xor ch.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }

    private fun messageHeightKey(message: ChatMessage, width: Int): MessageHeightKey? {
        if (width <= 0 || message.id == editingMessageId || isLiveAssistant(message)) return null
        val thinkingExpanded = message.id in messageThinkingExpanded
        return MessageHeightKey(
            id = message.id,
            width = width,
            roleHash = message.role.hashCode(),
            modelHash = message.modelName.hashCode(),
            contentLength = message.content.length,
            contentHash = message.content.hashCode(),
            thinkingLength = message.thinking.length,
            thinkingHash = message.thinking.hashCode(),
            thinkingExpanded = thinkingExpanded,
            uiMode = resources.configuration.uiMode
        )
    }

    private inner class MessageAdapter : RecyclerView.Adapter<MessageAdapter.MessageHolder>() {
        private val viewTypeLoadMore = 1
        private val viewTypeMessage = 2
        private val visibleMessages = mutableListOf<ChatMessage>()
        private var hiddenCount = 0
        private var visibleStartIndex = 0

        init {
            setHasStableIds(true)
        }

        fun submit(messages: List<ChatMessage>, startIndex: Int, hidden: Int) {
            visibleMessages.clear()
            visibleMessages.addAll(messages)
            visibleStartIndex = startIndex
            hiddenCount = hidden
            notifyDataSetChanged()
        }

        fun positionOfMessage(messageId: String): Int {
            val index = visibleMessages.indexOfFirst { it.id == messageId }
            if (index < 0) return RecyclerView.NO_POSITION
            return index + if (hiddenCount > 0) 1 else 0
        }

        fun notifyMessageChanged(messageId: String): Boolean {
            val position = positionOfMessage(messageId)
            if (position == RecyclerView.NO_POSITION) return false
            notifyItemChanged(position)
            return true
        }

        override fun getItemViewType(position: Int): Int {
            return if (hiddenCount > 0 && position == 0) viewTypeLoadMore else viewTypeMessage
        }

        override fun getItemId(position: Int): Long {
            if (getItemViewType(position) == viewTypeLoadMore) return Long.MIN_VALUE + hiddenCount
            val messageIndex = position - if (hiddenCount > 0) 1 else 0
            val message = visibleMessages.getOrNull(messageIndex) ?: return RecyclerView.NO_ID
            return stableMessageItemId(message.id)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageHolder {
            return MessageHolder(FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        }

        override fun onBindViewHolder(holder: MessageHolder, position: Int) {
            holder.boundMessageId?.let(::clearMessageViewBindings)
            holder.boundMessageId = null
            holder.container.removeAllViews()
            if (getItemViewType(position) == viewTypeLoadMore) {
                holder.container.addView(loadOlderHint(hiddenCount).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(12) }
                })
                return
            }
            val messageIndex = position - if (hiddenCount > 0) 1 else 0
            val message = visibleMessages.getOrNull(messageIndex) ?: return
            holder.boundMessageId = message.id
            val lockedHeight = editExitLockedHeights.remove(message.id)?.takeIf { it > 0 }
            val heightKey = messageHeightKey(message, messagesScroll.width)
            val cachedHeight = lockedHeight ?: heightKey?.let { messageHeightCache[it] }
            holder.container.minimumHeight = cachedHeight ?: 0
            holder.container.addView(messageCard(message, visibleStartIndex + messageIndex + 1, cachedHeight).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    cachedHeight ?: ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }
            })
            if (heightKey != null) {
                holder.container.doOnLayout {
                    val measured = holder.container.getChildAt(0)?.height ?: 0
                    if (measured > 0) messageHeightCache[heightKey] = measured
                }
            }
            if (streamTargets.containsKey(message.id)) scheduleVisibleStreamRefresh()
        }

        override fun onViewRecycled(holder: MessageHolder) {
            holder.boundMessageId?.let(::clearMessageViewBindings)
            holder.boundMessageId = null
            holder.container.removeAllViews()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = visibleMessages.size + if (hiddenCount > 0) 1 else 0

        inner class MessageHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
            var boundMessageId: String? = null
        }
    }

    private fun messageCard(message: ChatMessage, index: Int, lockedHeight: Int? = null): View {
        val isAssistant = message.role == "assistant"
        val editing = message.id == editingMessageId
        val accent = if (isAssistant) Color.rgb(100, 210, 255) else color(R.color.chat_accent_blue)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(
                color(R.color.chat_card),
                dp(14),
                dp(1),
                if (editing) color(R.color.chat_accent_blue) else color(R.color.chat_border)
            )
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

        val bodyViews = messageBody(message)
        contentColumn.addView(bodyViews.host)
        contentColumn.addView(separator().apply {
            if (editing) visibility = View.INVISIBLE
        })
        contentColumn.addView(messageFooter(message).apply {
            if (editing) {
                visibility = View.INVISIBLE
                isEnabled = false
            }
        })
        card.addView(contentColumn)
        lockedHeight?.let {
            card.minimumHeight = lockedHeight
        }
        if (recentlyInsertedMessageIds.remove(message.id)) {
            card.post { animateMessageEntrance(card) }
        } else if (editTransitionMessageIds.remove(message.id)) {
            card.post { animateEditCardTransition(card) }
        }
        return card
    }

    private fun messageBody(message: ChatMessage): StreamBodyViews {
        if (message.id == editingMessageId) return editableMessageBody(message)
        val content = displayContent(message)
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val prefix = messageBodyTextView()
        val tail = streamTailView()
        host.addView(prefix)
        host.addView(tail)
        val views = StreamBodyViews(host, prefix, tail)
        messageStreamBodyViews[message.id] = views
        if (isLiveAssistant(message)) {
            setStreamingBodyText(message.id, views, content)
        } else {
            prefix.text = formatMessageText(content)
            prefix.visibility = if (content.isEmpty()) View.GONE else View.VISIBLE
            tail.clearText()
            tail.visibility = View.GONE
        }
        messageRenderedContent[message.id] = content
        return views
    }

    private fun editableMessageBody(message: ChatMessage): StreamBodyViews {
        val draft = if (editingMessageId == message.id) editingMessageDraft else message.content
        val host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val edit = EditText(this).apply {
            setText(draft)
            setTextColor(color(R.color.chat_fg))
            setHintTextColor(color(R.color.chat_muted))
            textSize = 16f
            typeface = systemTypeface()
            includeFontPadding = true
            setLineSpacing(dp(4).toFloat(), 1.04f)
            minLines = 4
            maxLines = Int.MAX_VALUE
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(color(R.color.chat_subtle_surface), dp(10), dp(1), color(R.color.chat_accent_blue))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    setSelection(text?.length ?: 0)
                }
            }
        }
        installNestedEditScroll(edit)
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (editingMessageId == message.id) editingMessageDraft = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        host.addView(edit)
        host.alpha = 0f
        host.animate()
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(entranceInterpolator)
            .start()
        editingMessageEditor = edit
        messageRenderedContent[message.id] = draft
        return StreamBodyViews(
            host = host,
            prefix = messageBodyTextView().apply { visibility = View.GONE },
            tail = streamTailView().apply { visibility = View.GONE }
        )
    }

    private fun messageBodyTextView(): TextView {
        return TextView(this).apply {
            setTextColor(color(R.color.chat_fg))
            textSize = 16f
            typeface = systemTypeface()
            includeFontPadding = true
            setLineSpacing(dp(4).toFloat(), 1.04f)
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun streamTailView(): StreamTailView {
        return StreamTailView(this).apply {
            setRequestedFrameRate(currentRequestedFrameRate)
            setTextColorValue(color(R.color.chat_fg))
            setTextSizePxValue(16f * resources.displayMetrics.scaledDensity)
            typeface = systemTypeface()
            includeFontPadding = true
            lineSpacingExtra = dp(4).toFloat()
            lineSpacingMultiplier = 1.04f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
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
        if (isLiveAssistant(message)) {
            val target = streamTargets[message.id]
            if (target != null && (target.visibleContentLength > 0 || target.targetContent.isNotEmpty())) {
                return target.visibleContent
            }
        }
        if (message.role == "assistant" &&
            message.content.isBlank() &&
            isSending &&
            state.activeSession()?.history?.lastOrNull()?.id == message.id
        ) {
            return if (message.id in nonStreamingAssistantIds) {
                "非流式请求中，等待完整回复..."
            } else {
                "正在思考..."
            }
        }
        return message.content
    }

    private fun updateMessageViews(
        message: ChatMessage,
        streaming: Boolean = false,
        final: Boolean = false,
        revealFromTopWhenStuck: Boolean = false
    ) {
        val bottomDistance = remainingScrollToBottom()
        val stick = shouldFollowBottomForUpdate(bottomDistance)
        updateStreamDistanceModes(bottomDistance)
        followBottom = stick
        val anchor = if ((streaming || final) && !stick && !userTouchingMessages) captureScrollAnchor() else null
        val bodyUpdate = updateBodyText(message, streaming, final)
        updateThinkingPanel(message)
        anchor?.let { restoreScrollAnchorSoon(it) }
        if (stick) {
            when {
                revealFromTopWhenStuck && final && !streaming -> scrollMessageToTopSoon(message.id)
                streaming -> if (bodyUpdate.heightChanged) scrollToBottomDuringStream()
                else -> if (bodyUpdate.changed || final) scrollToBottomSoon()
            }
        }
    }

    private fun updateBodyText(message: ChatMessage, streaming: Boolean, final: Boolean): StreamBodyUpdate {
        val views = messageStreamBodyViews[message.id] ?: return StreamBodyUpdate()
        val content = displayContent(message)
        val previous = messageRenderedContent[message.id]
        if (streaming && !final) {
            if (previous == content) return StreamBodyUpdate()
            val heightChanged = setStreamingBodyText(
                message.id,
                views,
                content
            )
            messageRenderedContent[message.id] = content
            return StreamBodyUpdate(changed = true, heightChanged = heightChanged)
        }
        if (final || previous != content) {
            val wasStreamingBody = message.role == "assistant" &&
                (messageStreamSegments.containsKey(message.id) || !views.tail.isTextEmpty())
            stopStreamingBodyHeightAnimation(message.id, views.host)
            if (final && wasStreamingBody && content.isNotEmpty()) {
                val oldHeight = currentBodyHeight(views.host)
                val oldWidth = views.host.width
                if (oldHeight > 0 && oldWidth > 0) pinBodyHeight(views.host, oldHeight)
                val renderUpdate = renderStreamingBodySegments(message.id, views, content)
                views.tail.showCursor = false
                messageStreamSegments.remove(message.id)
                messageRenderedContent[message.id] = content
                val targetHeight = if (oldWidth > 0) measureStreamingBodyHeight(views, oldWidth) else 0
                if (targetHeight > 0) {
                    pinBodyHeight(views.host, targetHeight)
                } else {
                    ensureBodyWrapContent(views.host)
                }
                animateContentSettled(views.host)
                return StreamBodyUpdate(
                    changed = true,
                    heightChanged = renderUpdate.heightMayHaveChanged || targetHeight != oldHeight
                )
            }
            streamBodyMeasureStates.remove(message.id)
            messageStreamSegments.remove(message.id)
            views.prefix.text = formatMessageText(content)
            views.prefix.visibility = if (content.isEmpty()) View.GONE else View.VISIBLE
            views.tail.clearText()
            views.tail.visibility = View.GONE
            messageRenderedContent[message.id] = content
            if (final && message.role == "assistant") {
                animateContentSettled(views.host)
            }
            return StreamBodyUpdate(changed = true, heightChanged = true)
        }
        return StreamBodyUpdate()
    }

    private fun setStreamingBodyText(
        messageId: String,
        views: StreamBodyViews,
        rawContent: String
    ): Boolean {
        val oldHeight = currentBodyHeight(views.host)
        val oldWidth = views.host.width
        updateStreamContentWidth(views, oldWidth)
        if (oldHeight > 0 && oldWidth > 0) {
            pinBodyHeight(views.host, oldHeight)
        }
        val renderUpdate = renderStreamingBodySegments(messageId, views, rawContent)
        if (oldHeight <= 0 || oldWidth <= 0) {
            ensureBodyWrapContent(views.host)
            return true
        }
        if (!renderUpdate.heightMayHaveChanged) {
            return false
        }
        val targetHeight = measureStreamingBodyHeight(views, oldWidth)
        val activeTarget = streamBodyHeightTargets[messageId]
        val activeAnimation = streamBodyHeightAnimationTokens[messageId]
        if (activeTarget == targetHeight && activeAnimation != null) {
            return false
        }
        val fromHeight = currentBodyHeight(views.host)
        if (abs(targetHeight - fromHeight) > dp(1)) {
            streamBodyHeightAnimators.remove(messageId)?.cancel()
            streamBodyHeightAnimationTokens.remove(messageId)
            streamBodyHeightTargets.remove(messageId)
            pinBodyHeight(views.host, targetHeight)
            if (followBottom && !userTouchingMessages) {
                views.host.doOnLayout {
                    if (followBottom && !userTouchingMessages) scrollToBottomNow()
                }
            }
            return true
        }
        if (activeTarget == null && activeAnimation == null) {
            pinBodyHeight(views.host, targetHeight)
        }
        return false
    }

    private fun renderStreamingBodySegments(messageId: String, views: StreamBodyViews, rawContent: String): StreamSegmentUpdate {
        updateStreamContentWidth(views)
        val segment = messageStreamSegments.getOrPut(messageId) { StreamTextSegmentState() }
        if (rawContent.length < segment.frozenRawLength ||
            segment.prefixRaw.isNotEmpty() && !rawContent.startsWith(segment.prefixRaw)
        ) {
            segment.reset()
        }
        if (views.prefix.visibility != View.GONE) {
            views.prefix.text = ""
            views.prefix.visibility = View.GONE
            views.invalidatePrefixMeasure()
        }
        var heightMayHaveChanged = false
        val nextFreezeEnd = findStreamFreezeBoundary(rawContent, segment.frozenRawLength, views, segment)
        if (nextFreezeEnd > segment.frozenRawLength) {
            val prefixRaw = rawContent.substring(0, nextFreezeEnd)
            if (prefixRaw != segment.prefixRaw) {
                segment.prefixRendered = renderStreamPrefix(segment, prefixRaw)
                segment.prefixRaw = prefixRaw
                heightMayHaveChanged = true
            }
            segment.frozenRawLength = nextFreezeEnd
            segment.tailFormatter = newStreamFormatter()
            segment.tailRaw = ""
            segment.clearFreezeScanCache()
        }

        val tailRaw = rawContent.substring(segment.frozenRawLength.coerceIn(0, rawContent.length))
        var shouldUpdateText = false
        var renderedTail: CharSequence = segment.tailRendered
        if (tailRaw != segment.tailRaw ||
            tailRaw.isEmpty() && !views.tail.isTextEmpty() ||
            tailRaw.isNotEmpty() && views.tail.isTextEmpty()
        ) {
            val formatter = segment.tailFormatter ?: newStreamFormatter().also { segment.tailFormatter = it }
            renderedTail = formatter.render(tailRaw).text
            segment.tailRendered = renderedTail
            segment.tailRaw = tailRaw
            shouldUpdateText = true
        }
        if (heightMayHaveChanged || shouldUpdateText) {
            val oldVisibility = views.tail.visibility
            views.tail.showCursor = rawContent.isNotEmpty()
            val tailHeightChanged = views.tail.setStreamSegments(segment.prefixRendered, renderedTail)
            val nextVisibility = if (rawContent.isEmpty()) View.GONE else View.VISIBLE
            views.tail.visibility = nextVisibility
            heightMayHaveChanged = heightMayHaveChanged ||
                tailHeightChanged ||
                oldVisibility != nextVisibility
        }
        return StreamSegmentUpdate(heightMayHaveChanged)
    }

    private fun updateStreamContentWidth(views: StreamBodyViews, hostWidth: Int = views.host.width): Int {
        val measuredWidth = (hostWidth - views.host.paddingLeft - views.host.paddingRight)
            .takeIf { it >= dp(80) }
        val fallbackWidth = measuredWidth ?: views.contentWidth.takeIf { it >= dp(80) }
        val contentWidth = fallbackWidth ?: estimateStreamContentWidth()
        if (contentWidth >= dp(80) && contentWidth != views.contentWidth) {
            views.contentWidth = contentWidth
            views.tail.setStableContentWidth(contentWidth)
            views.invalidatePrefixMeasure()
        }
        return views.contentWidth.takeIf { it >= dp(80) } ?: contentWidth
    }

    private fun estimateStreamContentWidth(): Int {
        val scrollWidth = messagesScroll.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        return (scrollWidth - dp(76)).coerceAtLeast(dp(80))
    }

    private fun renderStreamPrefix(segment: StreamTextSegmentState, raw: String): CharSequence {
        if (raw.isEmpty()) return ""
        val formatter = segment.prefixFormatter ?: newStreamFormatter().also { segment.prefixFormatter = it }
        return SpannableStringBuilder(formatter.render(raw).text)
    }

    private fun findStreamFreezeBoundary(
        text: String,
        frozenEnd: Int,
        views: StreamBodyViews,
        segment: StreamTextSegmentState
    ): Int {
        if (text.length - frozenEnd <= streamTailMaxChars) return frozenEnd
        val contentWidth = (views.host.width - views.host.paddingLeft - views.host.paddingRight)
            .takeIf { it >= dp(80) }
            ?: updateStreamContentWidth(views)
        if (contentWidth <= 0) return findNewlineFreezeBoundary(text, frozenEnd)
        if (segment.freezeScanWidth == contentWidth &&
            segment.freezeScanFrozenEnd == frozenEnd &&
            segment.freezeScanBoundary == frozenEnd &&
            text.length - segment.freezeScanTextLength < streamFreezeScanStepChars
        ) {
            return frozenEnd
        }
        val scanEnd = (text.length - streamTailTargetChars).coerceAtLeast(frozenEnd)
        val mapping = buildStreamDisplayMapping(text, scanEnd)
        if (mapping.text.isEmpty()) return frozenEnd
        var boundary = frozenEnd
        forEachLineBreakEnd(mapping.text, views.prefix.paint, contentWidth) { renderedEnd ->
            if (renderedEnd > 0) {
                val rawEnd = mapping.rawEnds[renderedEnd - 1].coerceIn(0, scanEnd)
                val endsWithNewline = rawEnd > 0 && text[rawEnd - 1] == '\n'
                val truncatedLastLine = renderedEnd >= mapping.text.length &&
                    rawEnd >= scanEnd &&
                    scanEnd < text.length &&
                    !endsWithNewline
                if (rawEnd > frozenEnd &&
                    rawEnd >= streamFrozenPrefixMinChars &&
                    !truncatedLastLine &&
                    mapping.isPlainAt(rawEnd)
                ) {
                    boundary = rawEnd
                }
            }
        }
        segment.freezeScanWidth = contentWidth
        segment.freezeScanFrozenEnd = frozenEnd
        segment.freezeScanTextLength = text.length
        segment.freezeScanBoundary = boundary
        return boundary
    }

    private fun forEachLineBreakEnd(
        text: String,
        paint: TextPaint,
        width: Int,
        consume: (Int) -> Unit
    ) {
        if (text.isEmpty()) return
        val breaker = LineBreaker.Builder()
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(LineBreaker.HYPHENATION_FREQUENCY_NONE)
            .setUseBoundsForWidth(false)
            .build()
        var paragraphStart = 0
        while (paragraphStart < text.length) {
            val newline = text.indexOf('\n', paragraphStart)
            val paragraphEnd = if (newline >= 0) newline else text.length
            if (paragraphEnd > paragraphStart) {
                val paragraph = text.substring(paragraphStart, paragraphEnd)
                val measured = MeasuredText.Builder(paragraph.toCharArray())
                    .appendStyleRun(paint, streamLineBreakConfig, paragraph.length, false)
                    .build()
                val constraints = LineBreaker.ParagraphConstraints().apply {
                    setWidth(width.toFloat().coerceAtLeast(1f))
                }
                val result = breaker.computeLineBreaks(measured, constraints, 0)
                for (line in 0 until result.lineCount) {
                    val paragraphLineEnd = result.getLineBreakOffset(line).coerceIn(0, paragraph.length)
                    var renderedEnd = paragraphStart + paragraphLineEnd
                    if (newline >= 0 && renderedEnd == paragraphEnd) renderedEnd = newline + 1
                    consume(renderedEnd.coerceIn(0, text.length))
                }
            } else if (newline >= 0) {
                consume((newline + 1).coerceIn(0, text.length))
            }
            if (newline < 0) break
            paragraphStart = newline + 1
        }
    }

    private fun findNewlineFreezeBoundary(text: String, frozenEnd: Int): Int {
        val scanEnd = (text.length - streamTailTargetChars).coerceAtLeast(frozenEnd)
        var boundary = frozenEnd
        var mode = StreamMode.PLAIN
        var index = frozenEnd.coerceIn(0, scanEnd)
        while (index < scanEnd) {
            val ch = text[index]
            mode = nextStreamMode(mode, ch)
            if (ch == '\n') {
                var end = index + 1
                while (end < scanEnd && text[end] == '\n') end += 1
                if (end > frozenEnd && end >= streamFrozenPrefixMinChars && mode == StreamMode.PLAIN) {
                    boundary = end
                }
                index = end
            } else {
                index += 1
            }
        }
        return boundary
    }

    private fun buildStreamDisplayMapping(text: String, rawEndExclusive: Int): StreamDisplayMapping {
        val rawLimit = rawEndExclusive.coerceIn(0, text.length)
        val rendered = StringBuilder(rawLimit)
        val rawEnds = ArrayList<Int>(rawLimit)
        val plainAfterRaw = BooleanArray(rawLimit + 1)
        var mode = StreamMode.PLAIN
        var specialStart = -1
        plainAfterRaw[0] = true
        fun append(ch: Char, rawIndex: Int) {
            rendered.append(ch)
            rawEnds.add(rawIndex + 1)
        }
        for (index in 0 until rawLimit) {
            val ch = text[index]
            when {
                mode == StreamMode.PLAIN && (ch == '"' || ch == '\uFF02') -> {
                    mode = StreamMode.DOUBLE_QUOTE
                    specialStart = rendered.length
                    append(ch, index)
                }
                mode == StreamMode.DOUBLE_QUOTE &&
                    (ch == '"' || ch == '\uFF02' || ch == '\u201C' || ch == '\u201D') -> {
                    append(ch, index)
                    mode = StreamMode.PLAIN
                    specialStart = -1
                }
                mode == StreamMode.PLAIN && ch == '\u201C' -> {
                    mode = StreamMode.CHINESE_QUOTE
                    specialStart = rendered.length
                    append(ch, index)
                }
                mode == StreamMode.CHINESE_QUOTE &&
                    (ch == '\u201D' || ch == '"' || ch == '\uFF02' || ch == '\u201C') -> {
                    append(ch, index)
                    mode = StreamMode.PLAIN
                    specialStart = -1
                }
                mode == StreamMode.PLAIN && (ch == '*' || ch == '\uFF0A') -> {
                    mode = StreamMode.STAR
                    specialStart = rendered.length
                    append(ch, index)
                }
                mode == StreamMode.STAR && (ch == '*' || ch == '\uFF0A') -> {
                    if (specialStart in 0 until rendered.length) {
                        rendered.deleteCharAt(specialStart)
                        rawEnds.removeAt(specialStart)
                    }
                    mode = StreamMode.PLAIN
                    specialStart = -1
                }
                else -> append(ch, index)
            }
            plainAfterRaw[index + 1] = mode == StreamMode.PLAIN
        }
        return StreamDisplayMapping(rendered.toString(), rawEnds.toIntArray(), plainAfterRaw)
    }

    private fun nextStreamMode(mode: StreamMode, ch: Char): StreamMode {
        return when {
            mode == StreamMode.PLAIN && (ch == '"' || ch == '\uFF02') -> StreamMode.DOUBLE_QUOTE
            mode == StreamMode.DOUBLE_QUOTE && (ch == '"' || ch == '\uFF02' || ch == '\u201C' || ch == '\u201D') -> StreamMode.PLAIN
            mode == StreamMode.PLAIN && ch == '\u201C' -> StreamMode.CHINESE_QUOTE
            mode == StreamMode.CHINESE_QUOTE && (ch == '\u201D' || ch == '"' || ch == '\uFF02' || ch == '\u201C') -> StreamMode.PLAIN
            mode == StreamMode.PLAIN && (ch == '*' || ch == '\uFF0A') -> StreamMode.STAR
            mode == StreamMode.STAR && (ch == '*' || ch == '\uFF0A') -> StreamMode.PLAIN
            else -> mode
        }
    }

    private fun isStreamFreezeChar(ch: Char): Boolean {
        return ch == '\n' ||
            ch == '.' ||
            ch == '!' ||
            ch == '?' ||
            ch == ';' ||
            ch == '\u3002' ||
            ch == '\uFF01' ||
            ch == '\uFF1F' ||
            ch == '\uFF1B'
    }

    private fun currentBodyHeight(body: View): Int {
        val pinned = body.layoutParams?.height ?: 0
        return if (pinned > 0) pinned else body.height
    }

    private fun estimateStreamingTextHeightForSegments(
        messageId: String,
        views: StreamBodyViews,
        width: Int,
        text: String
    ): Int {
        val state = streamBodyMeasureStates.getOrPut(messageId) { StreamBodyMeasureState() }
        val reset = state.width != width ||
            text.length < state.textLength ||
            state.lineHeight <= 0
        val start = if (reset) {
            resetSegmentLineEstimate(state, views, width)
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

    private fun resetSegmentLineEstimate(state: StreamBodyMeasureState, views: StreamBodyViews, width: Int) {
        val contentWidth = (width - views.host.paddingLeft - views.host.paddingRight).coerceAtLeast(dp(80))
        val averageCharWidth = views.prefix.paint.measureText("\u4E2D").coerceAtLeast(1f)
        state.width = width
        state.textLength = 0
        state.estimatedLines = 1
        state.lineUnits = 0f
        state.unitsPerLine = (contentWidth / averageCharWidth).coerceAtLeast(4f)
        state.lineHeight = views.prefix.lineHeight.coerceAtLeast(1)
        state.baseHeight = views.host.paddingTop + views.host.paddingBottom + dp(3)
        state.targetHeight = state.baseHeight + state.lineHeight
    }

    private fun measureStreamingBodyHeight(views: StreamBodyViews, width: Int): Int {
        val contentWidth = (width - views.host.paddingLeft - views.host.paddingRight).coerceAtLeast(dp(80))
        val prefixHeight = measureStreamingPrefixHeight(views, contentWidth)
        val tailHeight = if (views.tail.visibility == View.VISIBLE) {
            views.tail.desiredHeightForWidth(contentWidth)
        } else {
            0
        }
        return views.host.paddingTop + views.host.paddingBottom + prefixHeight + tailHeight
    }

    private fun measureStreamingPrefixHeight(views: StreamBodyViews, contentWidth: Int): Int {
        if (views.prefix.visibility != View.VISIBLE || views.prefix.text.isEmpty()) return 0
        val textLength = views.prefix.text.length
        if (views.prefixMeasuredWidth == contentWidth &&
            views.prefixMeasuredTextLength == textLength &&
            views.prefixMeasuredHeight > 0
        ) {
            return views.prefixMeasuredHeight
        }
        views.prefix.measure(
            View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        views.prefixMeasuredWidth = contentWidth
        views.prefixMeasuredTextLength = textLength
        views.prefixMeasuredHeight = views.prefix.measuredHeight
        return views.prefixMeasuredHeight
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

    private fun animateStreamingBodyHeight(messageId: String, body: View, fromHeight: Int, toHeight: Int) {
        streamBodyHeightAnimators.remove(messageId)?.cancel()
        val token = ++streamHeightAnimationSeed
        streamBodyHeightAnimationTokens[messageId] = token
        streamBodyHeightTargets[messageId] = toHeight
        pinBodyHeight(body, fromHeight)
        if (!followBottom) {
            val anchor = captureScrollAnchor()
            finishStreamingBodyHeightAnimation(messageId, body, token, toHeight)
            anchor?.let { restoreScrollAnchorSoon(it) }
            return
        }
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
                if (followBottom && !userTouchingMessages) scrollToBottomDuringStream()
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

    private fun finishStreamingBodyHeightAnimation(messageId: String, body: View, token: Int, toHeight: Int) {
        if (streamBodyHeightAnimationTokens[messageId] != token) return
        streamBodyHeightAnimators.remove(messageId)
        streamBodyHeightAnimationTokens.remove(messageId)
        streamBodyHeightTargets.remove(messageId)
        pinBodyHeight(body, toHeight)
        if (followBottom && !userTouchingMessages) scrollToBottomNow()
    }

    private fun stopStreamingBodyHeightAnimation(messageId: String, body: View) {
        streamBodyHeightAnimators.remove(messageId)?.cancel()
        streamBodyHeightAnimationTokens.remove(messageId)
        streamBodyHeightTargets.remove(messageId)
        streamBodyMeasureStates.remove(messageId)
        ensureBodyWrapContent(body)
    }

    private fun pinBodyHeight(body: View, height: Int) {
        val params = body.layoutParams ?: return
        if (params.height != height) {
            params.height = height
            body.layoutParams = params
        }
    }

    private fun ensureBodyWrapContent(body: View) {
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
        target.contentBuffer.replaceWith(content)
        target.thinkingBuffer.replaceWith(thinking)
        target.contentBuffer.compactIfNeeded()
        target.thinkingBuffer.compactIfNeeded()
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
        val bound = isStreamTargetBound(message.id)
        target.forceNextUiCommit = followBottom || streamLiveModeNow
        refreshDisplayPerformanceMode()
        if (bound && shouldUpdateBoundStreamTarget()) {
            scheduleStreamAnimator()
        }
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
        val delayMs = streamAnimatorDelayMs()
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

    private fun streamAnimatorDelayMs(): Long {
        val distance = remainingScrollToBottom()
        val liveMode = distance < dp(streamLiveDistanceDp)
        val throttledMode = distance < dp(streamThrottledDistanceDp)
        streamLiveModeNow = liveMode
        streamThrottledModeNow = throttledMode
        refreshDisplayPerformanceMode()
        return when {
            !throttledMode -> streamOffscreenUiFrameMs
            liveMode -> streamRevealFrameMs
            else -> streamThrottledUiFrameMs
        }
    }

    private fun tickStreamAnimator() {
        val hintStartNs = beginUiWorkHint()
        try {
            val now = android.os.SystemClock.uptimeMillis()
            var needsNextFrame = false
            val distance = remainingScrollToBottom()
            val liveMode = distance < dp(streamLiveDistanceDp)
            val throttledMode = distance < dp(streamThrottledDistanceDp)
            streamLiveModeNow = liveMode
            streamThrottledModeNow = throttledMode
            refreshDisplayPerformanceMode()
            val iterator = streamTargets.entries.iterator()
            while (iterator.hasNext()) {
                val target = iterator.next().value
                if (!throttledMode) {
                    if (target.finishWhenCaught && !isStreamTargetBound(target.message.id)) {
                        iterator.remove()
                        messageStreamSegments.remove(target.message.id)
                    }
                    continue
                }
                val targetVisible = isStreamTargetVisible(target.message.id)
                refreshContentReleaseEnd(target)
                val elapsedMs = revealElapsedMs(target, now)
                val contentEnd = target.contentReleaseEnd.coerceIn(0, target.targetContent.length)
                val contentStepLimit = streamContentMaxCharsPerTick
                if (!targetVisible) {
                    val targetBound = isStreamTargetBound(target.message.id)
                    val shouldThrottledUpdate = targetBound &&
                        shouldCommitOffscreenStreamFrame(target, now)
                    if (shouldThrottledUpdate) {
                        val nextContentLength = if (target.contentPrefixValid) {
                            advanceVisibleLength(target.visibleContentLength, contentEnd, contentStepLimit)
                        } else {
                            contentEnd
                        }
                        commitStreamFrame(
                            target,
                            now,
                            nextContentLength,
                            target.targetThinking.length,
                            streaming = !target.finishWhenCaught
                        )
                        target.lastOffscreenUiCommitAt = now
                        val caughtUp = nextContentLength == target.targetContent.length &&
                            target.visibleThinkingLength == target.targetThinking.length
                        if (caughtUp && target.finishWhenCaught) {
                            iterator.remove()
                            messageStreamSegments.remove(target.message.id)
                        } else if (target.targetContent.length > target.visibleContentLength ||
                            target.targetThinking.length > target.visibleThinkingLength
                        ) {
                            needsNextFrame = true
                        }
                    } else if (target.finishWhenCaught && !targetBound) {
                        iterator.remove()
                        messageStreamSegments.remove(target.message.id)
                    } else if (targetBound && shouldUpdateBoundStreamTarget()) {
                        needsNextFrame = true
                    }
                    continue
                }
                val thinkingExpanded = target.message.id in messageThinkingExpanded
                val thinkingInterval = if (thinkingExpanded) streamExpandedThinkingFrameMs else streamCollapsedThinkingFrameMs
                val thinkingDue = target.finishWhenCaught ||
                    !target.thinkingPrefixValid ||
                    now - target.lastThinkingUiCommitAt >= thinkingInterval
                val thinkingBudget = if (thinkingDue) {
                    revealBudget(
                        target.thinkingRevealCarry,
                        streamThinkingCharsPerSecond,
                        elapsedMs,
                        streamMaxThinkingCharsPerFrame
                    )
                } else {
                    RevealBudget(0, target.thinkingRevealCarry)
                }
                target.thinkingRevealCarry = thinkingBudget.carry
                val nextContentLength = if (target.contentPrefixValid) {
                    advanceVisibleLength(target.visibleContentLength, contentEnd, contentStepLimit)
                } else {
                    contentEnd
                }
                val nextThinkingLength = if (target.thinkingPrefixValid && !target.finishWhenCaught) {
                    advanceVisibleLength(target.visibleThinkingLength, target.targetThinking.length, thinkingBudget.chars)
                } else {
                    target.targetThinking.length
                }
                val contentChanged = nextContentLength != target.visibleContentLength || !target.contentPrefixValid
                val thinkingChanged = nextThinkingLength != target.visibleThinkingLength || !target.thinkingPrefixValid
                val changed = contentChanged || thinkingChanged
                if (changed) {
                    if (shouldCommitStreamFrame(target, now, contentChanged, thinkingChanged, nextContentLength, nextThinkingLength)) {
                        commitStreamFrame(target, now, nextContentLength, nextThinkingLength, streaming = true)
                    } else {
                        needsNextFrame = true
                    }
                }
                val caughtUp = nextContentLength == target.targetContent.length && nextThinkingLength == target.targetThinking.length
                if (caughtUp && target.finishWhenCaught) {
                    iterator.remove()
                    messageStreamSegments.remove(target.message.id)
                    updateMessageViews(target.message, final = true)
                } else if (!caughtUp || target.targetContent.length > target.contentReleaseEnd) {
                    needsNextFrame = true
                }
            }
            if (needsNextFrame) scheduleStreamAnimator()
        } finally {
            finishUiWorkHint(hintStartNs)
            refreshDisplayPerformanceMode()
        }
    }

    private fun commitStreamFrame(
        target: StreamRenderTarget,
        now: Long,
        nextContentLength: Int,
        nextThinkingLength: Int,
        streaming: Boolean
    ) {
        val nextContent = target.contentBuffer.prefixAt(nextContentLength, target.targetContent)
        val nextThinking = target.thinkingBuffer.prefixAt(nextThinkingLength, target.targetThinking)
        val thinkingChanged = nextThinkingLength != target.visibleThinkingLength
        target.visibleContentLength = nextContentLength
        target.visibleThinkingLength = nextThinkingLength
        target.visibleContent = nextContent
        target.visibleThinking = nextThinking
        target.contentPrefixValid = true
        target.thinkingPrefixValid = true
        target.lastUiCommitAt = now
        target.lastRevealAt = now
        target.forceNextUiCommit = false
        if (thinkingChanged) target.lastThinkingUiCommitAt = now
        val display = target.message.copy(content = nextContent, thinking = nextThinking)
        updateMessageViews(display, streaming = streaming, final = !streaming)
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
        if (target.forceNextUiCommit) return true
        if (target.lastUiCommitAt == 0L) return true
        if (target.finishWhenCaught &&
            nextContentLength == target.targetContent.length &&
            nextThinkingLength == target.targetThinking.length
        ) {
            return true
        }
        val minInterval = when {
            userTouchingMessages -> streamRevealTouchFrameMs
            streamLiveModeNow -> streamUiCommitFrameMs
            else -> streamThrottledUiFrameMs
        }
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

    private fun newStreamFormatter(): StreamFormatState {
        return StreamFormatState(
            quoteColor = color(R.color.chat_accent3),
            starColor = color(R.color.chat_accent)
        )
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
            message.id !in nonStreamingAssistantIds &&
            state.activeSession()?.history?.lastOrNull()?.id == message.id
    }

    private fun formatMessageText(text: String): CharSequence {
        if (text.isEmpty()) return text
        val key = TextFormatCacheKey(
            text = text,
            quoteColor = color(R.color.chat_accent3),
            starColor = color(R.color.chat_accent)
        )
        formattedTextCache[key]?.let { return it }
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
        return out.also { formattedTextCache[key] = it }
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

    private fun isNearBottom(distance: Int = remainingScrollToBottom()): Boolean {
        return distance < dp(64)
    }

    private fun updateStreamDistanceModes(distance: Int = remainingScrollToBottom()) {
        streamLiveModeNow = distance < dp(streamLiveDistanceDp)
        streamThrottledModeNow = distance < dp(streamThrottledDistanceDp)
        refreshDisplayPerformanceMode()
    }

    private fun shouldUpdateBoundStreamTarget(): Boolean {
        return followBottom || streamThrottledModeNow
    }

    private fun shouldFollowBottomForUpdate(distance: Int = remainingScrollToBottom()): Boolean {
        if (userTouchingMessages) return false
        return followBottom || isNearBottom(distance)
    }

    private fun detachBottomFollowForTouch() {
        followBottom = false
        streamBottomFollowToken++
        streamBottomFollowScheduled = false
        bottomScrollScheduled = false
        lastBottomTargetY = -1
        messagesScroll.stopScroll()
    }

    private fun captureScrollAnchor(): ScrollAnchor? {
        val position = messagesLayoutManager.findFirstVisibleItemPosition()
        if (position == RecyclerView.NO_POSITION) return null
        val view = messagesLayoutManager.findViewByPosition(position) ?: return null
        return ScrollAnchor(
            position = position,
            offset = view.top - messagesScroll.paddingTop,
            absoluteOffset = messagesScroll.computeVerticalScrollOffset()
        )
    }

    private fun isStreamTargetVisible(messageId: String): Boolean {
        val host = messageStreamBodyViews[messageId]?.host ?: return false
        if (!host.isShown || !ViewCompat.isAttachedToWindow(host)) return false
        val hostRect = Rect()
        val listRect = Rect()
        if (!host.getGlobalVisibleRect(hostRect)) return false
        if (!messagesScroll.getGlobalVisibleRect(listRect)) return false
        return hostRect.intersect(listRect) && hostRect.height() > dp(2)
    }

    private fun isStreamTargetBound(messageId: String): Boolean {
        val host = messageStreamBodyViews[messageId]?.host ?: return false
        return ViewCompat.isAttachedToWindow(host)
    }

    private fun shouldCommitOffscreenStreamFrame(target: StreamRenderTarget, now: Long): Boolean {
        if (target.finishWhenCaught) return true
        val contentDelta = (target.targetContent.length - target.visibleContentLength).coerceAtLeast(0)
        val thinkingDelta = (target.targetThinking.length - target.visibleThinkingLength).coerceAtLeast(0)
        if (contentDelta == 0 && thinkingDelta == 0) return false
        if (target.lastOffscreenUiCommitAt == 0L) return true
        if (contentDelta >= streamOffscreenFlushChars || thinkingDelta >= streamThinkingFlushChars) return true
        return now - target.lastOffscreenUiCommitAt >= streamOffscreenUiFrameMs
    }

    private fun restoreScrollAnchorSoon(anchor: ScrollAnchor) {
        messagesScroll.post {
            if (userTouchingMessages || followBottom || isNearBottom()) return@post
            keepProgrammaticScrollActive()
            messagesLayoutManager.scrollToPositionWithOffset(anchor.position, anchor.offset)
        }
    }

    private fun restoreScrollAnchorAfterEdit(anchor: ScrollAnchor) {
        val token = ++editScrollRestoreToken
        val delays = longArrayOf(0L, 32L, 96L, 180L, 320L, 520L, 760L)
        delays.forEach { delay ->
            mainHandler.postDelayed({
                if (token != editScrollRestoreToken || userTouchingMessages) return@postDelayed
                keepProgrammaticScrollActive()
                followBottom = false
                messagesLayoutManager.scrollToPositionWithOffset(anchor.position, anchor.offset)
                messagesScroll.post {
                    if (token != editScrollRestoreToken || userTouchingMessages) return@post
                    val delta = anchor.absoluteOffset - messagesScroll.computeVerticalScrollOffset()
                    if (delta != 0) {
                        keepProgrammaticScrollActive()
                        messagesScroll.scrollBy(0, delta)
                    }
                }
                updateStreamDistanceModes()
            }, delay)
        }
    }

    private fun scrollMessageToTopSoon(messageId: String) {
        if (userTouchingMessages) return
        messagesScroll.post {
            if (userTouchingMessages) return@post
            val position = messagesAdapter.positionOfMessage(messageId)
            if (position == RecyclerView.NO_POSITION) return@post
            keepProgrammaticScrollActive()
            messagesLayoutManager.scrollToPositionWithOffset(position, dp(8))
            followBottom = false
            updateStreamDistanceModes()
        }
    }

    private fun scrollMessageToTopForEdit(messageId: String) {
        val token = ++editScrollRestoreToken
        messagesScroll.post {
            if (token != editScrollRestoreToken || userTouchingMessages) return@post
            val position = messagesAdapter.positionOfMessage(messageId)
            if (position == RecyclerView.NO_POSITION) return@post
            keepProgrammaticScrollActive()
            followBottom = false
            updateStreamDistanceModes()
            val view = messagesLayoutManager.findViewByPosition(position)
            if (view != null) {
                val dy = view.top - messagesScroll.paddingTop - dp(8)
                if (abs(dy) > dp(2)) {
                    messagesScroll.smoothScrollBy(0, dy)
                } else {
                    messagesLayoutManager.scrollToPositionWithOffset(position, dp(8))
                }
            } else {
                messagesScroll.smoothScrollToPosition(position)
                messagesScroll.postDelayed({
                    if (token == editScrollRestoreToken && !userTouchingMessages) {
                        keepProgrammaticScrollActive()
                        messagesLayoutManager.scrollToPositionWithOffset(position, dp(8))
                    }
                }, 220L)
            }
        }
    }

    private fun scrollToBottomSoon(animated: Boolean = false) {
        if (userTouchingMessages) return
        if (bottomScrollScheduled) return
        bottomScrollScheduled = true
        messagesScroll.post {
            bottomScrollScheduled = false
            if (userTouchingMessages) return@post
            if (scrollRemainingToBottom(animated)) {
                followBottom = true
                streamLiveModeNow = true
                streamThrottledModeNow = true
                return@post
            }
            followBottom = true
            streamLiveModeNow = true
            streamThrottledModeNow = true
        }
    }

    private fun scrollToBottomNow() {
        if (userTouchingMessages) return
        scrollRemainingToBottom(animated = false)
        followBottom = true
        streamLiveModeNow = true
        streamThrottledModeNow = true
    }

    private fun scrollRemainingToBottom(animated: Boolean): Boolean {
        if (messagesAdapter.itemCount == 0) return false
        val distance = remainingScrollToBottom()
        if (distance <= dp(1)) return true
        if (distance == lastBottomTargetY && abs(distance) <= dp(1)) return true
        lastBottomTargetY = distance
        keepProgrammaticScrollActive()
        if (messagesScroll.computeVerticalScrollRange() <= messagesScroll.height) {
            messagesLayoutManager.scrollToPositionWithOffset(messagesAdapter.itemCount - 1, 0)
        } else if (animated) {
            messagesScroll.smoothScrollBy(0, distance)
        } else {
            messagesScroll.scrollBy(0, distance)
        }
        return true
    }

    private fun remainingScrollToBottom(): Int {
        val range = messagesScroll.computeVerticalScrollRange()
        val extent = messagesScroll.computeVerticalScrollExtent()
        val offset = messagesScroll.computeVerticalScrollOffset()
        if (range <= 0 || extent <= 0) return 0
        return (range - extent - offset).coerceAtLeast(0)
    }

    private fun scrollToBottomDuringStream() {
        if (!followBottom) return
        if (userTouchingMessages) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastStreamBottomScrollAt >= streamBottomScrollFrameMs) {
            lastStreamBottomScrollAt = now
            scrollToBottomNow()
        }
        if (streamBottomFollowScheduled) return
        streamBottomFollowScheduled = true
        val token = streamBottomFollowToken
        ViewCompat.postOnAnimation(messagesScroll) {
            streamBottomFollowScheduled = false
            if (token != streamBottomFollowToken) return@postOnAnimation
            if (followBottom && !userTouchingMessages) scrollToBottomNow()
        }
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
                if (!followBottom || userTouchingMessages) followBottom = isNearBottom()
                updateStreamDistanceModes()
            }
        }
        mainHandler.postDelayed({ releaseWhenQuiet() }, scrollTrackFrameMs)
    }

    @SuppressLint("NewApi")
    private fun animateCompositeLayer(view: View, durationMs: Long) {
        if (view.width <= 0 || view.height <= 0 || !ViewCompat.isAttachedToWindow(view)) return
        val shader = runCatching { RuntimeShader(compositeRevealShaderSource) }.getOrNull() ?: return
        val effect = runCatching { RenderEffect.createRuntimeShaderEffect(shader, "input") }.getOrNull() ?: return
        val token = ++compositeEffectTokenSeed
        compositeEffectTokens[view] = token
        shader.setFloatUniform("size", view.width.toFloat(), view.height.toFloat())
        shader.setFloatUniform("progress", 0f)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.setRenderEffect(effect)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = entranceInterpolator
            addUpdateListener { animation ->
                if (compositeEffectTokens[view] != token) return@addUpdateListener
                shader.setFloatUniform("progress", animation.animatedValue as Float)
                view.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = clear()
                override fun onAnimationCancel(animation: Animator) = clear()

                private fun clear() {
                    if (compositeEffectTokens[view] != token) return
                    compositeEffectTokens.remove(view)
                    view.setRenderEffect(null)
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
            start()
        }
    }

    private fun animateIn(view: View) {
        animateCompositeLayer(view, 560L)
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

    private fun animateMessageEntrance(view: View) {
        view.animate().cancel()
        animateCompositeLayer(view, 340L)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.alpha = 0f
        view.scaleX = 0.995f
        view.scaleY = 0.995f
        view.translationY = dp(14).toFloat()
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(340)
            .setInterpolator(entranceInterpolator)
            .withEndAction { view.setLayerType(View.LAYER_TYPE_NONE, null) }
            .start()
    }

    private fun animateEditCardTransition(view: View) {
        view.animate().cancel()
        animateCompositeLayer(view, 300L)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.alpha = 0.9f
        view.animate()
            .alpha(1f)
            .setDuration(260)
            .setInterpolator(entranceInterpolator)
            .withEndAction { view.setLayerType(View.LAYER_TYPE_NONE, null) }
            .start()
        animateMessageFrameSettled(view)
    }

    private fun animateContentSettled(view: View) {
        view.animate().cancel()
        animateCompositeLayer(view, 520L)
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.alpha = 0.82f
        view.scaleX = 0.999f
        view.scaleY = 0.999f
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(520)
            .setInterpolator(entranceInterpolator)
            .withEndAction { view.setLayerType(View.LAYER_TYPE_NONE, null) }
            .start()
        findMessageCardForBody(view)?.let { animateMessageFrameSettled(it) }
    }

    private fun findMessageCardForBody(body: View): View? {
        val contentColumn = body.parent as? View ?: return null
        val card = contentColumn.parent as? View ?: return null
        return card.takeIf { it.isShown && ViewCompat.isAttachedToWindow(it) }
    }

    private fun animateMessageFrameSettled(card: View) {
        val overlay = rounded(Color.rgb(96, 165, 250), dp(14), dp(1), color(R.color.chat_accent_blue))
        card.foreground = overlay
        card.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ValueAnimator.ofInt(54, 0).apply {
            duration = 680L
            interpolator = entranceInterpolator
            addUpdateListener { animation ->
                overlay.alpha = animation.animatedValue as Int
                card.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    card.foreground = null
                    card.setLayerType(View.LAYER_TYPE_NONE, null)
                }

                override fun onAnimationCancel(animation: Animator) {
                    card.foreground = null
                    card.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
        }.start()
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
        if (editingMessageId == message.id) {
            finishEditingMessage()
            return
        }
        messagesScroll.stopScroll()
        val previousEditingId = editingMessageId
        if (previousEditingId != null) {
            lockEditExitHeight(previousEditingId)
            commitEditingDraft(previousEditingId)
            editTransitionMessageIds.add(previousEditingId)
        }
        followBottom = false
        editingMessageId = message.id
        editingMessageDraft = message.content
        editingMessageEditor = null
        editTransitionMessageIds.add(message.id)
        setEditingFloatingButtonVisible(true)
        refreshMessagesPreservingScroll(
            listOfNotNull(previousEditingId, message.id),
            anchor = null
        )
        scrollMessageToTopForEdit(message.id)
    }

    private fun finishEditingMessage() {
        val id = editingMessageId ?: return
        val anchor = captureScrollAnchor()
        lockEditExitHeight(id)
        messagesScroll.stopScroll()
        followBottom = false
        commitEditingDraft(id)
        editTransitionMessageIds.add(id)
        editingMessageId = null
        editingMessageDraft = ""
        editingMessageEditor = null
        val inputMethod = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethod.hideSoftInputFromWindow(root.windowToken, 0)
        repository.save(state)
        setEditingFloatingButtonVisible(false)
        refreshMessagesPreservingScroll(listOf(id), anchor, editMode = true)
        renderSessions()
    }

    private fun lockEditExitHeight(messageId: String) {
        val height = currentBoundMessageCard(messageId)?.height ?: 0
        if (height > 0) editExitLockedHeights[messageId] = height
    }

    private fun currentBoundMessageCard(messageId: String): View? {
        val position = messagesAdapter.positionOfMessage(messageId)
        if (position == RecyclerView.NO_POSITION) return null
        val holder = messagesLayoutManager.findViewByPosition(position) as? ViewGroup ?: return null
        return holder.getChildAt(0)
    }

    private fun commitEditingDraft(messageId: String) {
        val text = editingMessageEditor?.text?.toString() ?: editingMessageDraft
        state.activeSession()?.history?.firstOrNull { it.id == messageId }?.content = text
        repository.save(state)
    }

    private fun refreshMessagesPreservingScroll(
        messageIds: List<String>,
        anchor: ScrollAnchor?,
        editMode: Boolean = false
    ) {
        var usedPartialRefresh = true
        messageIds.distinct().forEach { id ->
            usedPartialRefresh = messagesAdapter.notifyMessageChanged(id) && usedPartialRefresh
        }
        if (!usedPartialRefresh) {
            renderMessages(scrollToBottom = false)
        }
        if (anchor != null) {
            if (editMode) restoreScrollAnchorAfterEdit(anchor) else restoreScrollAnchorSoon(anchor)
        }
    }

    private fun setEditingFloatingButtonVisible(visible: Boolean) {
        if (visible) {
            val button = editingFloatingButton ?: TextView(this).apply {
                text = "完成"
                setTextColor(Color.WHITE)
                textSize = 13f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
                gravity = Gravity.CENTER
                background = roundedGradient(color(R.color.chat_accent_blue), Color.rgb(59, 130, 246), dp(8))
                elevation = dp(8).toFloat()
                installPressAnimation(this)
                setOnClickListener { finishEditingMessage() }
            }.also { editingFloatingButton = it }
            if (button.parent == null) {
                root.addView(button)
            }
            positionEditingFloatingButton(button)
            messagesScroll.setPadding(
                messagesScroll.paddingLeft,
                messagesScroll.paddingTop,
                messagesScroll.paddingRight,
                baseMessagesBottomPadding + dp(62)
            )
            button.animate().cancel()
            button.alpha = 0f
            button.translationY = dp(10).toFloat()
            button.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(260)
                .setInterpolator(entranceInterpolator)
                .start()
            mainHandler.post { positionEditingFloatingButton(button) }
        } else {
            messagesScroll.setPadding(
                messagesScroll.paddingLeft,
                messagesScroll.paddingTop,
                messagesScroll.paddingRight,
                baseMessagesBottomPadding
            )
            val button = editingFloatingButton ?: return
            button.animate().cancel()
            button.animate()
                .alpha(0f)
                .translationY(dp(8).toFloat())
                .setDuration(180)
                .setInterpolator(softInterpolator)
                .withEndAction {
                    (button.parent as? ViewGroup)?.removeView(button)
                }
                .start()
        }
    }

    private fun positionEditingFloatingButton(button: View) {
        val composerHeight = if (composer.height > 0) composer.height else dp(132)
        button.layoutParams = FrameLayout.LayoutParams(dp(74), dp(40), Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dp(18)
            bottomMargin = composerHeight + dp(14)
        }
    }

    private fun deleteMessage(message: ChatMessage) {
        val stick = isNearBottom()
        val anchor = if (stick) null else captureScrollAnchor()
        if (editingMessageId == message.id) {
            editingMessageId = null
            editingMessageDraft = ""
            editingMessageEditor = null
            setEditingFloatingButtonVisible(false)
        }
        state.activeSession()?.history?.removeAll { it.id == message.id }
        repository.save(state)
        renderMessages(scrollToBottom = stick)
        anchor?.let { restoreScrollAnchorSoon(it) }
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
                    if (editingMessageId != null) finishEditingMessage()
                    state.currentId = session.id
                    renderMessageLimit = defaultRenderMessageLimit
                    repository.save(state)
                    renderAll()
                    setSidebarVisible(false)
                }
            }
            installPressAnimation(this)

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
                addView(tinyButton("打开", primary = active).apply {
                    setOnClickListener {
                        if (!isSending) {
                            if (editingMessageId != null) finishEditingMessage()
                            state.currentId = session.id
                            renderMessageLimit = defaultRenderMessageLimit
                            repository.save(state)
                            renderAll()
                            setSidebarVisible(false)
                        }
                    }
                })
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
        footer.addView(dialogButton("关闭").apply { setOnClickListener { dialog.dismiss() } })
        footer.addView(dialogButton("应用", primary = true).apply {
            setOnClickListener {
                if (saveCurrentPane?.invoke() == false) return@setOnClickListener
                repository.save(state)
                renderAll()
                toast("已保存")
            }
        })

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

        dialog.setContentView(shell)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            val width = min(resources.displayMetrics.widthPixels - dp(24), dp(960))
            val height = min(resources.displayMetrics.heightPixels - dp(72), dp(720))
            dialog.window?.setLayout(width, height)
            shell.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            shell.alpha = 0f
            shell.translationY = dp(14).toFloat()
            animateCompositeLayer(shell, 320L)
            shell.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320)
                .setInterpolator(entranceInterpolator)
                .withEndAction {
                    shell.setLayerType(View.LAYER_TYPE_NONE, null)
                    if (dialog.isShowing) selectTab(0)
                }
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
        host.addView(dialogButton("+ 新增预设", primary = true).apply {
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
            listHost.addView(dialogButton("+ 新增预设", primary = true).apply {
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
            listHost.addView(dialogButton("删除当前", danger = true).apply {
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
                if (selected) Color.argb(44, 96, 165, 250) else color(R.color.chat_card),
                dp(8),
                dp(1),
                if (selected) color(R.color.chat_accent_blue) else color(R.color.chat_border)
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            addView(TextView(context).apply {
                text = preset.name.ifBlank { key }
                setTextColor(if (selected) color(R.color.chat_accent_blue) else color(R.color.chat_fg))
                textSize = 13f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = desc
                setTextColor(color(R.color.chat_muted))
                textSize = 11f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
                includeFontPadding = false
            })
            installPressAnimation(this)
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
        host.addView(dialogButton("+ 添加规则", primary = true).apply {
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
        card.addView(dialogButton("删除规则", danger = true).apply {
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
        host.addView(settingsActionCard(
            title = "导出数据",
            description = "导出模型预设、提示词预设和对话记录为 JSON 文件。",
            actions = listOf(
                dialogButton("导出所有数据", primary = true).apply { setOnClickListener { exportBackup("all") } },
                dialogButton("仅导出对话记录").apply { setOnClickListener { exportBackup("sessions") } },
                dialogButton("仅导出配置预设").apply { setOnClickListener { exportBackup("presets") } }
            )
        ))
        host.addView(settingsActionCard(
            title = "导入数据",
            description = "从 JSON 备份文件恢复数据。",
            actions = listOf(
                dialogButton("合并导入", primary = true).apply {
                    setOnClickListener {
                        pendingImportReplace = false
                        dialog.dismiss()
                        importLauncher.launch(arrayOf("application/json", "text/*"))
                    }
                },
                dialogButton("替换导入", danger = true).apply {
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
                }
            )
        ))
        host.addView(settingsActionCard(
            title = "危险操作",
            description = "清空当前所有对话、模型预设、提示词预设和配置。",
            actions = listOf(
                dialogButton("清空所有数据", danger = true).apply {
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
                }
            )
        ))
        return { true }
    }

    private fun settingsActionCard(title: String, description: String, actions: List<View>): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(color(R.color.chat_card), dp(12), dp(1), color(R.color.chat_border))
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            addView(TextView(context).apply {
                text = title
                setTextColor(color(R.color.chat_accent_blue))
                textSize = 15f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = description
                setTextColor(color(R.color.chat_muted))
                textSize = 13f
                setLineSpacing(dp(3).toFloat(), 1.04f)
                setPadding(0, dp(14), 0, dp(12))
            })
            actions.forEach { action ->
                addView(action.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(10) }
                })
            }
        }
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
            addView(TextView(context).apply {
                text = "预设${editor.index + 1}"
                setTextColor(color(R.color.chat_accent_blue))
                textSize = 14f
                typeface = systemTypeface(Typeface.BOLD)
                includeFontPadding = false
                setPadding(0, 0, 0, dp(12))
            })
            editor.rows.forEach { addView(it) }
        }
    }

    private fun tinyButton(label: String, danger: Boolean = false, primary: Boolean = false): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(if (danger || primary) Color.WHITE else color(R.color.chat_fg))
            textSize = 12f
            typeface = systemTypeface(Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = when {
                danger -> roundedGradient(color(R.color.chat_danger), Color.rgb(220, 38, 38), dp(6))
                primary -> roundedGradient(color(R.color.chat_accent_blue), Color.rgb(59, 130, 246), dp(6))
                else -> rounded(color(R.color.chat_panel), dp(6), dp(1), color(R.color.chat_border))
            }
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

    private fun dialogButton(label: String, primary: Boolean = false, danger: Boolean = false): TextView {
        return TextView(this).apply {
            text = label
            setTextColor(if (primary || danger) Color.WHITE else color(R.color.chat_fg))
            textSize = 13f
            typeface = systemTypeface(Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            background = when {
                danger -> roundedGradient(color(R.color.chat_danger), Color.rgb(220, 38, 38), dp(8))
                primary -> roundedGradient(color(R.color.chat_accent_blue), Color.rgb(59, 130, 246), dp(8))
                else -> rounded(color(R.color.chat_panel), dp(8), dp(1), color(R.color.chat_border))
            }
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
            if (multiLine) installNestedEditScroll(this)
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

    @SuppressLint("ClickableViewAccessibility")
    private fun installNestedEditScroll(input: EditText) {
        input.isVerticalScrollBarEnabled = true
        input.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        var lastY = 0f
        input.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastY = event.y
                    if (view.canScrollVertically(1) || view.canScrollVertically(-1)) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - lastY
                    lastY = event.y
                    val scrollingDown = dy < 0f
                    val canScroll = if (scrollingDown) view.canScrollVertically(1) else view.canScrollVertically(-1)
                    view.parent?.requestDisallowInterceptTouchEvent(canScroll)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            false
        }
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
                        .scaleX(0.972f)
                        .scaleY(0.972f)
                        .translationY(resources.displayMetrics.density * 0.5f)
                        .alpha(0.92f)
                        .setDuration(90)
                        .setInterpolator(softInterpolator)
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    animateCompositeLayer(target, 220L)
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .alpha(1f)
                        .setDuration(220)
                        .setInterpolator(entranceInterpolator)
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

    private fun roundedGradient(
        @ColorInt startColor: Int,
        @ColorInt endColor: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor)
        ).apply {
            cornerRadius = radius.toFloat()
        }
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private class PerformanceWorkSession(
        private val session: PerformanceHintManager.Session,
        private val startNs: Long
    ) {
        @SuppressLint("NewApi")
        fun finish() {
            val elapsedNs = (SystemClock.uptimeNanos() - startNs).coerceAtLeast(1L)
            runCatching {
                val duration = WorkDuration().apply {
                    setWorkPeriodStartTimestampNanos(startNs)
                    setActualTotalDurationNanos(elapsedNs)
                    setActualCpuDurationNanos(elapsedNs)
                    setActualGpuDurationNanos(0L)
                }
                session.reportActualWorkDuration(duration)
            }
            runCatching { session.close() }
        }
    }

    private class LruCacheMap<K, V>(private val maxEntries: Int) : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }
    }

    private data class MessageHeightKey(
        val id: String,
        val width: Int,
        val roleHash: Int,
        val modelHash: Int,
        val contentLength: Int,
        val contentHash: Int,
        val thinkingLength: Int,
        val thinkingHash: Int,
        val thinkingExpanded: Boolean,
        val uiMode: Int
    )

    private data class TextFormatCacheKey(
        val text: String,
        val quoteColor: Int,
        val starColor: Int
    )

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

    private data class ScrollAnchor(
        val position: Int,
        val offset: Int,
        val absoluteOffset: Int
    )

    private data class StreamBodyViews(
        val host: LinearLayout,
        val prefix: TextView,
        val tail: StreamTailView,
        var prefixMeasuredWidth: Int = -1,
        var prefixMeasuredTextLength: Int = -1,
        var prefixMeasuredHeight: Int = 0,
        var contentWidth: Int = -1
    ) {
        fun invalidatePrefixMeasure() {
            prefixMeasuredWidth = -1
            prefixMeasuredTextLength = -1
            prefixMeasuredHeight = 0
        }
    }

    private data class StreamBodyUpdate(
        val changed: Boolean = false,
        val heightChanged: Boolean = false
    )

    private data class StreamSegmentUpdate(
        val heightMayHaveChanged: Boolean = false
    )

    private data class StreamTextSegmentState(
        var frozenRawLength: Int = 0,
        var prefixRaw: String = "",
        var tailRaw: String = "",
        var prefixRendered: CharSequence = "",
        var tailRendered: CharSequence = "",
        var prefixFormatter: StreamFormatState? = null,
        var tailFormatter: StreamFormatState? = null,
        var freezeScanWidth: Int = -1,
        var freezeScanFrozenEnd: Int = -1,
        var freezeScanTextLength: Int = 0,
        var freezeScanBoundary: Int = 0
    ) {
        fun reset() {
            frozenRawLength = 0
            prefixRaw = ""
            tailRaw = ""
            prefixRendered = ""
            tailRendered = ""
            prefixFormatter = null
            tailFormatter = null
            clearFreezeScanCache()
        }

        fun clearFreezeScanCache() {
            freezeScanWidth = -1
            freezeScanFrozenEnd = -1
            freezeScanTextLength = 0
            freezeScanBoundary = 0
        }
    }

    private class StreamTailView(context: Context) : View(context) {
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var prefixText: CharSequence = ""
        private var tailText: CharSequence = ""
        private var cachedPrefixLayout: StreamMeasuredLayout? = null
        private var cachedTailLayout: StreamMeasuredLayout? = null
        private var cachedPrefixWidth = -1
        private var cachedTailWidth = -1
        private val paragraphCache = ParagraphRenderCache(160)
        private val lineBreakConfig = LineBreakConfig.Builder()
            .setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_NONE)
            .setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_NONE)
            .setHyphenation(LineBreakConfig.HYPHENATION_DISABLED)
            .build()
        private val lineBreaker = LineBreaker.Builder()
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(LineBreaker.HYPHENATION_FREQUENCY_NONE)
            .setUseBoundsForWidth(false)
            .build()
        private val density = resources.displayMetrics.density
        private val minUsefulContentWidth = (80f * density).toInt().coerceAtLeast(1)
        private val cursorGap = 2f * density
        private val cursorWidth = (2f * density).coerceAtLeast(1f)
        private val cursorInset = 3f * density
        private val cursorBlinkMs = 560L
        private var stableContentWidth = 0
        private var lastKnownContentWidth = 0

        init {
            textPaint.density = resources.displayMetrics.density
            cursorPaint.color = ContextCompat.getColor(context, R.color.chat_accent_blue)
        }

        var showCursor: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        var typeface: Typeface?
            get() = textPaint.typeface
            set(value) {
                textPaint.typeface = value
                invalidateLayouts()
            }

        var includeFontPadding: Boolean = true
            set(value) {
                if (field == value) return
                field = value
                invalidateLayouts()
            }

        var lineSpacingExtra: Float = 0f
            set(value) {
                if (field == value) return
                field = value
                invalidateLayouts()
            }

        var lineSpacingMultiplier: Float = 1f
            set(value) {
                if (field == value) return
                field = value
                invalidateLayouts()
            }

        fun setTextColorValue(@ColorInt color: Int) {
            if (textPaint.color == color) return
            textPaint.color = color
            invalidateLayouts(clearParagraphCache = true)
        }

        fun setTextSizePxValue(sizePx: Float) {
            if (textPaint.textSize == sizePx) return
            textPaint.textSize = sizePx
            invalidateLayouts()
        }

        fun clearText(): Boolean {
            return setStreamText("")
        }

        fun isTextEmpty(): Boolean = prefixText.isEmpty() && tailText.isEmpty()

        fun setStableContentWidth(contentWidth: Int): Boolean {
            if (contentWidth < minUsefulContentWidth || stableContentWidth == contentWidth) return false
            stableContentWidth = contentWidth
            lastKnownContentWidth = contentWidth
            invalidateLayouts()
            return true
        }

        fun desiredHeightForWidth(contentWidth: Int): Int {
            if (isTextEmpty()) return 0
            if (contentWidth >= minUsefulContentWidth) setStableContentWidth(contentWidth)
            val width = resolveContentWidth(contentWidth + paddingLeft + paddingRight)
            return paddingTop + layoutHeight(prefixLayoutForWidth(width)) + layoutHeight(tailLayoutForWidth(width)) + paddingBottom
        }

        fun setStreamText(value: CharSequence): Boolean {
            return setStreamSegments("", value)
        }

        fun setStreamSegments(prefix: CharSequence, tail: CharSequence): Boolean {
            if (TextUtils.equals(prefixText, prefix) && TextUtils.equals(tailText, tail)) return false
            val oldHeight = (cachedPrefixLayout?.height ?: 0) + (cachedTailLayout?.height ?: 0)
            val prefixChanged = !TextUtils.equals(prefixText, prefix)
            val tailChanged = !TextUtils.equals(tailText, tail)
            prefixText = SpannableStringBuilder(prefix)
            tailText = SpannableStringBuilder(tail)
            if (prefixChanged) {
                cachedPrefixLayout = null
                cachedPrefixWidth = -1
            }
            if (tailChanged) {
                cachedTailLayout = null
                cachedTailWidth = -1
            }
            val contentWidth = resolveContentWidth(width)
            if (contentWidth <= 0 || !isShown || !ViewCompat.isAttachedToWindow(this)) {
                if (prefixChanged || tailChanged) requestLayout()
                invalidate()
                return true
            }
            val nextHeight = layoutHeight(prefixLayoutForWidth(contentWidth)) +
                layoutHeight(tailLayoutForWidth(contentWidth))
            val heightChanged = nextHeight != oldHeight
            if (heightChanged) {
                requestLayout()
            }
            invalidate()
            return heightChanged
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val measuredWidth = View.MeasureSpec.getSize(widthMeasureSpec)
            val contentWidth = resolveContentWidth(measuredWidth)
            val desiredWidth = contentWidth + paddingLeft + paddingRight
            val desiredHeight = paddingTop +
                layoutHeight(prefixLayoutForWidth(contentWidth)) +
                layoutHeight(tailLayoutForWidth(contentWidth)) +
                paddingBottom
            setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(if (isTextEmpty()) 0 else desiredHeight, heightMeasureSpec)
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (isTextEmpty()) return
            val contentWidth = resolveContentWidth(width)
            val prefixLayout = prefixLayoutForWidth(contentWidth)
            val tailLayout = tailLayoutForWidth(contentWidth)
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
            prefixLayout?.draw(canvas)
            val prefixHeight = layoutHeight(prefixLayout)
            if (tailLayout != null) {
                if (prefixHeight > 0) canvas.translate(0f, prefixHeight.toFloat())
                tailLayout.draw(canvas)
                drawCursor(canvas, tailLayout, contentWidth)
            } else {
                drawCursor(canvas, prefixLayout, contentWidth)
            }
            canvas.restore()
            if (showCursor && isShown && ViewCompat.isAttachedToWindow(this)) {
                postInvalidateDelayed(cursorBlinkMs)
            }
        }

        private fun drawCursor(canvas: Canvas, layout: StreamMeasuredLayout?, contentWidth: Int) {
            if (!showCursor) return
            if (layout == null) return
            if ((android.os.SystemClock.uptimeMillis() / cursorBlinkMs) % 2L != 0L) return
            val x = (layout.lastLineRight + cursorGap)
                .coerceAtMost(contentWidth - cursorWidth)
                .coerceAtLeast(0f)
            val top = layout.lastLineTop + cursorInset
            val bottom = layout.lastLineBottom - cursorInset
            if (bottom <= top) return
            canvas.drawRoundRect(
                x,
                top,
                x + cursorWidth,
                bottom,
                cursorWidth / 2f,
                cursorWidth / 2f,
                cursorPaint
            )
        }

        private fun prefixLayoutForWidth(width: Int): StreamMeasuredLayout? {
            if (prefixText.isEmpty()) return null
            cachedPrefixLayout?.let {
                if (cachedPrefixWidth == width) return it
            }
            val layout = buildLayout(prefixText, width)
            cachedPrefixLayout = layout
            cachedPrefixWidth = width
            return layout
        }

        private fun tailLayoutForWidth(width: Int): StreamMeasuredLayout? {
            if (tailText.isEmpty()) return null
            cachedTailLayout?.let {
                if (cachedTailWidth == width) return it
            }
            val layout = buildLayout(tailText, width)
            cachedTailLayout = layout
            cachedTailWidth = width
            return layout
        }

        private fun buildLayout(text: CharSequence, width: Int): StreamMeasuredLayout {
            return StreamMeasuredLayout(
                source = SpannableStringBuilder(text),
                width = width,
                basePaint = textPaint,
                includeFontPadding = includeFontPadding,
                lineSpacingExtra = lineSpacingExtra,
                lineSpacingMultiplier = lineSpacingMultiplier,
                lineBreakConfig = lineBreakConfig,
                lineBreaker = lineBreaker,
                paragraphCache = paragraphCache
            )
        }

        private fun layoutHeight(layout: StreamMeasuredLayout?): Int = layout?.height ?: 0

        private fun rawContentWidth(outerWidth: Int): Int {
            return outerWidth - paddingLeft - paddingRight
        }

        private fun resolveContentWidth(outerWidth: Int): Int {
            if (stableContentWidth >= minUsefulContentWidth) return stableContentWidth
            val candidate = rawContentWidth(outerWidth)
            if (candidate >= minUsefulContentWidth) {
                lastKnownContentWidth = candidate
                return candidate
            }
            if (lastKnownContentWidth >= minUsefulContentWidth) return lastKnownContentWidth
            val displayFallback = resources.displayMetrics.widthPixels - (96f * density).toInt()
            return displayFallback.coerceAtLeast(minUsefulContentWidth)
        }

        private fun invalidateLayouts(clearParagraphCache: Boolean = false) {
            cachedPrefixLayout = null
            cachedTailLayout = null
            cachedPrefixWidth = -1
            cachedTailWidth = -1
            if (clearParagraphCache) paragraphCache.clearAndDiscard()
            requestLayout()
            invalidate()
        }

        override fun onDetachedFromWindow() {
            paragraphCache.clearAndDiscard()
            super.onDetachedFromWindow()
        }

        private class StreamMeasuredLayout(
            private val source: CharSequence,
            private val width: Int,
            basePaint: TextPaint,
            includeFontPadding: Boolean,
            lineSpacingExtra: Float,
            lineSpacingMultiplier: Float,
            private val lineBreakConfig: LineBreakConfig,
            private val lineBreaker: LineBreaker,
            private val paragraphCache: ParagraphRenderCache
        ) {
            private val paint = TextPaint(basePaint)
            private val fontMetrics = paint.fontMetricsInt
            private val fontTop = if (includeFontPadding) fontMetrics.top else fontMetrics.ascent
            private val fontBottom = if (includeFontPadding) fontMetrics.bottom else fontMetrics.descent
            private val lineHeight = (((fontBottom - fontTop).coerceAtLeast(1) + lineSpacingExtra) *
                lineSpacingMultiplier).toInt().coerceAtLeast(1)
            private val paragraphs: List<ParagraphRender>
            val height: Int
            val lastLineRight: Float
            val lastLineTop: Float
            val lastLineBottom: Float

            init {
                val built = ArrayList<ParagraphRender>()
                var y = 0
                if (source.isNotEmpty()) {
                    var start = 0
                    while (start <= source.length) {
                        val newline = indexOfNewline(source, start)
                        val end = if (newline >= 0) newline else source.length
                        val paragraph = source.subSequence(start, end)
                        val render = buildParagraph(paragraph, y)
                        built.add(render)
                        y += render.height
                        if (newline < 0) break
                        start = newline + 1
                        if (start == source.length) {
                            val blank = buildParagraph("", y)
                            built.add(blank)
                            y += blank.height
                            break
                        }
                    }
                }
                paragraphs = built
                height = y
                val last = paragraphs.lastOrNull()
                lastLineRight = last?.snapshot?.lastLineRight ?: 0f
                lastLineTop = (last?.top ?: 0) + (last?.snapshot?.lastLineTop ?: 0f)
                lastLineBottom = (last?.top ?: 0) + (last?.snapshot?.lastLineBottom ?: 0f)
            }

            fun draw(canvas: Canvas) {
                paragraphs.forEach { it.draw(canvas, paint) }
            }

            private fun buildParagraph(paragraph: CharSequence, top: Int): ParagraphRender {
                val key = ParagraphRenderKey(
                    text = paragraph.toString(),
                    spans = spanSignature(paragraph),
                    width = width,
                    textSizeBits = paint.textSize.toRawBits(),
                    color = paint.color,
                    typefaceStyle = paint.typeface?.style ?: Typeface.NORMAL,
                    lineHeight = lineHeight,
                    fontTop = fontTop,
                    includeFontPadding = fontTop != fontMetrics.ascent || fontBottom != fontMetrics.descent
                )
                val snapshot = paragraphCache.getOrBuild(key) {
                    val lines = breakParagraphIntoLines(paragraph)
                    val paragraphHeight = (lines.lastOrNull()?.bottom ?: lineHeight).coerceAtLeast(lineHeight)
                    val node = recordParagraphNode(paragraph, lines, width, paragraphHeight, paint)
                    ParagraphRenderSnapshot(
                        text = SpannableStringBuilder(paragraph),
                        lines = lines,
                        height = paragraphHeight,
                        lastLineRight = lines.lastOrNull()?.right ?: 0f,
                        lastLineTop = lines.lastOrNull()?.top?.toFloat() ?: 0f,
                        lastLineBottom = lines.lastOrNull()?.bottom?.toFloat() ?: lineHeight.toFloat(),
                        node = node
                    )
                }
                return ParagraphRender(top, snapshot)
            }

            private fun breakParagraphIntoLines(paragraph: CharSequence): List<TextLine> {
                if (paragraph.isEmpty()) {
                    return listOf(TextLine(0, 0, -fontTop.toFloat(), 0, lineHeight, 0f))
                }
                val text = paragraph.toString()
                val chars = text.toCharArray()
                val measured = MeasuredText.Builder(chars)
                    .appendStyleRun(paint, lineBreakConfig, chars.size, false)
                    .build()
                val constraints = LineBreaker.ParagraphConstraints().apply {
                    setWidth(width.toFloat().coerceAtLeast(1f))
                }
                val result = lineBreaker.computeLineBreaks(measured, constraints, 0)
                val lineEnds = ArrayList<Int>(result.lineCount.coerceAtLeast(1))
                for (lineIndex in 0 until result.lineCount) {
                    lineEnds.add(result.getLineBreakOffset(lineIndex).coerceIn(0, text.length))
                }
                val lines = ArrayList<TextLine>(result.lineCount.coerceAtLeast(1))
                var lineStart = 0
                var y = 0
                for (lineIndex in lineEnds.indices) {
                    var lineEnd = lineEnds[lineIndex].coerceIn(lineStart, text.length)
                    if (lineEnd <= lineStart && lineStart < text.length) {
                        lineEnd = lineStart + 1
                    }
                    lines.add(
                        TextLine(
                            start = lineStart,
                            end = lineEnd,
                            baseline = y - fontTop.toFloat(),
                            top = y,
                            bottom = y + lineHeight,
                            right = result.getLineWidth(lineIndex).coerceAtLeast(0f)
                        )
                    )
                    y += lineHeight
                    lineStart = lineEnd
                }
                if (lines.isEmpty()) {
                    lines.add(TextLine(0, text.length, -fontTop.toFloat(), 0, lineHeight, paint.measureText(text)))
                }
                return lines
            }

            private fun recordParagraphNode(
                paragraph: CharSequence,
                lines: List<TextLine>,
                width: Int,
                height: Int,
                paint: TextPaint
            ): RenderNode? {
                return runCatching {
                    RenderNode("stream-paragraph").apply {
                        setPosition(0, 0, width, height)
                        val recordingCanvas: RecordingCanvas = beginRecording(width, height)
                        try {
                            drawParagraphLines(recordingCanvas, paragraph, lines, paint)
                        } finally {
                            endRecording()
                        }
                    }
                }.getOrNull()
            }

            private fun drawParagraphLines(
                canvas: Canvas,
                paragraph: CharSequence,
                lines: List<TextLine>,
                paint: TextPaint
            ) {
                lines.forEach { line ->
                    if (line.end > line.start) {
                        drawStyledText(canvas, paragraph, line.start, line.end, line.baseline, paint)
                    }
                }
            }

            private fun drawStyledText(
                canvas: Canvas,
                text: CharSequence,
                start: Int,
                end: Int,
                baseline: Float,
                paint: TextPaint
            ) {
                val oldColor = paint.color
                var x = 0f
                if (text is Spanned) {
                    var cursor = start
                    while (cursor < end) {
                        val next = text.nextSpanTransition(cursor, end, ForegroundColorSpan::class.java)
                        val spanColor = text.getSpans(cursor, next, ForegroundColorSpan::class.java)
                            .lastOrNull()
                            ?.foregroundColor
                        paint.color = spanColor ?: oldColor
                        canvas.drawText(text, cursor, next, x, baseline, paint)
                        x += paint.measureText(text, cursor, next)
                        cursor = next
                    }
                } else {
                    canvas.drawText(text, start, end, 0f, baseline, paint)
                }
                paint.color = oldColor
            }

            private fun ParagraphRender.draw(canvas: Canvas, paint: TextPaint) {
                canvas.save()
                canvas.translate(0f, top.toFloat())
                val node = snapshot.node
                if (canvas.isHardwareAccelerated && node != null && node.hasDisplayList()) {
                    canvas.drawRenderNode(node)
                } else {
                    drawParagraphLines(canvas, snapshot.text, snapshot.lines, paint)
                }
                canvas.restore()
            }

            private fun indexOfNewline(text: CharSequence, start: Int): Int {
                var index = start
                while (index < text.length) {
                    if (text[index] == '\n') return index
                    index += 1
                }
                return -1
            }

            private fun spanSignature(text: CharSequence): String {
                if (text !is Spanned) return ""
                val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
                if (spans.isEmpty()) return ""
                val out = StringBuilder(spans.size * 12)
                spans.forEach { span ->
                    out.append(text.getSpanStart(span))
                        .append(':')
                        .append(text.getSpanEnd(span))
                        .append(':')
                        .append(span.foregroundColor)
                        .append(';')
                }
                return out.toString()
            }
        }

        private data class ParagraphRender(
            val top: Int,
            val snapshot: ParagraphRenderSnapshot
        ) {
            val height: Int get() = snapshot.height
        }

        private data class ParagraphRenderSnapshot(
            val text: CharSequence,
            val lines: List<TextLine>,
            val height: Int,
            val lastLineRight: Float,
            val lastLineTop: Float,
            val lastLineBottom: Float,
            val node: RenderNode?
        )

        private data class TextLine(
            val start: Int,
            val end: Int,
            val baseline: Float,
            val top: Int,
            val bottom: Int,
            val right: Float
        )

        private data class ParagraphRenderKey(
            val text: String,
            val spans: String,
            val width: Int,
            val textSizeBits: Int,
            val color: Int,
            val typefaceStyle: Int,
            val lineHeight: Int,
            val fontTop: Int,
            val includeFontPadding: Boolean
        )

        private class ParagraphRenderCache(private val maxEntries: Int) :
            LinkedHashMap<ParagraphRenderKey, ParagraphRenderSnapshot>(maxEntries, 0.75f, true) {
            fun getOrBuild(key: ParagraphRenderKey, builder: () -> ParagraphRenderSnapshot): ParagraphRenderSnapshot {
                get(key)?.let { return it }
                val value = builder()
                put(key, value)
                return value
            }

            fun clearAndDiscard() {
                values.forEach { it.node?.discardDisplayList() }
                clear()
            }

            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ParagraphRenderKey, ParagraphRenderSnapshot>?): Boolean {
                val shouldRemove = size > maxEntries
                if (shouldRemove) eldest?.value?.node?.discardDisplayList()
                return shouldRemove
            }
        }
    }

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
        var thinkingRevealCarry: Float = 0f,
        var lastUiCommitAt: Long = 0L,
        var lastThinkingUiCommitAt: Long = 0L,
        var lastOffscreenUiCommitAt: Long = 0L,
        var forceNextUiCommit: Boolean = false,
        var contentPrefixValid: Boolean = true,
        var thinkingPrefixValid: Boolean = true,
        var releaseScanStart: Int = -1,
        var releaseScanLength: Int = -1,
        var releaseScanBoundary: Int = -1,
        val contentBuffer: StreamTextBuffer = StreamTextBuffer(),
        val thinkingBuffer: StreamTextBuffer = StreamTextBuffer()
    )

    private class StreamTextBuffer {
        private val chunks = ArrayDeque<String>()
        private var cachedFull: String = ""
        var length: Int = 0
            private set

        fun replaceWith(value: String) {
            if (value.length >= length && value.startsWith(cachedFull)) {
                val delta = value.substring(length)
                if (delta.isNotEmpty()) {
                    chunks.addLast(delta)
                    cachedFull = value
                    length = value.length
                }
                return
            }
            chunks.clear()
            if (value.isNotEmpty()) chunks.addLast(value)
            cachedFull = value
            length = value.length
        }

        fun prefixAt(prefixLength: Int, fallback: String): String {
            val end = prefixLength.coerceIn(0, length)
            if (end == length) return cachedFull.ifEmpty { fallback.substring(0, min(end, fallback.length)) }
            if (chunks.isEmpty()) return fallback.substring(0, min(end, fallback.length))
            val out = StringBuilder(end)
            var remaining = end
            val iterator = chunks.iterator()
            while (iterator.hasNext() && remaining > 0) {
                val chunk = iterator.next()
                val take = min(remaining, chunk.length)
                out.append(chunk, 0, take)
                remaining -= take
            }
            return out.toString()
        }

        fun compactIfNeeded(maxChunks: Int = 64) {
            if (chunks.size <= maxChunks) return
            chunks.clear()
            if (cachedFull.isNotEmpty()) chunks.addLast(cachedFull)
        }
    }

    private data class RevealBudget(
        val chars: Int,
        val carry: Float
    )

    private data class StreamRenderResult(
        val text: CharSequence,
        val changedStart: Int,
        val changedEnd: Int
    )

    private data class StreamDisplayMapping(
        val text: String,
        val rawEnds: IntArray,
        val plainAfterRaw: BooleanArray
    ) {
        fun isPlainAt(rawEnd: Int): Boolean {
            return rawEnd in plainAfterRaw.indices && plainAfterRaw[rawEnd]
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as StreamDisplayMapping
            return text == other.text &&
                rawEnds.contentEquals(other.rawEnds) &&
                plainAfterRaw.contentEquals(other.plainAfterRaw)
        }

        override fun hashCode(): Int {
            var result = text.hashCode()
            result = 31 * result + rawEnds.contentHashCode()
            result = 31 * result + plainAfterRaw.contentHashCode()
            return result
        }
    }

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

    private enum class DisplayPerformanceMode {
        IDLE,
        STREAM_BACKGROUND,
        TOUCH_SCROLL,
        STREAM_LIVE
    }

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
