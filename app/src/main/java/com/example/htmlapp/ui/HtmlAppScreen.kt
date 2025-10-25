package com.example.htmlapp.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalNavDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private val AccentBlue = Color(0xFF60A5FA)
private val AccentGold = Color(0xFFFBBF24)
private val DangerRed = Color(0xFFEF4444)

private val PanelBrush = Brush.verticalGradient(
    0f to Color(0xFF151921),
    1f to Color(0xFF050608)
)

private val HeaderBrush = Brush.verticalGradient(
    0f to Color(0xFA151921),
    1f to Color(0xFF000000)
)

data class ChatSessionUi(
    val id: String,
    val title: String,
    val subtitle: String,
    val isActive: Boolean = false,
)

enum class ChatRole { User, Assistant }

data class ChatMessageUi(
    val id: String,
    val role: ChatRole,
    val content: String,
    val modelLabel: String? = null,
    val thinking: String? = null,
    val isStreaming: Boolean = false,
)

data class ModelPresetUi(
    val id: String,
    val displayName: String,
)

data class HtmlAppUiState(
    val sessions: List<ChatSessionUi>,
    val selectedSessionId: String?,
    val availableModels: List<ModelPresetUi>,
    val selectedModelId: String,
    val messages: List<ChatMessageUi>,
    val composerText: String,
    val isSending: Boolean,
    val canLoadMore: Boolean,
    val isSettingsVisible: Boolean,
    val statusMessage: String?,
    val apiKey: String,
)

@Composable
fun HtmlAppScreen(
    modifier: Modifier = Modifier,
    state: HtmlAppUiState,
    onSelectSession: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onComposerChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopStreaming: () -> Unit,
    onLoadMore: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissSettings: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onDismissStatus: () -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        LaunchedStatusMessage(state.statusMessage, onDismissStatus)
        if (state.isSettingsVisible) {
            SettingsDialog(
                apiKey = state.apiKey,
                onApiKeyChange = onApiKeyChange,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
                onDismiss = onDismissSettings,
            )
        }
        val isCompact = maxWidth < 820.dp
        if (isCompact) {
            HtmlAppCompactLayout(
                state = state,
                onSelectSession = onSelectSession,
                onSelectModel = onSelectModel,
                onComposerChange = onComposerChange,
                onSendMessage = onSendMessage,
                onStopStreaming = onStopStreaming,
                onLoadMore = onLoadMore,
                onNewChat = onNewChat,
                onOpenSettings = onOpenSettings,
            )
        } else {
            HtmlAppWideLayout(
                state = state,
                onSelectSession = onSelectSession,
                onSelectModel = onSelectModel,
                onComposerChange = onComposerChange,
                onSendMessage = onSendMessage,
                onStopStreaming = onStopStreaming,
                onLoadMore = onLoadMore,
                onNewChat = onNewChat,
                onOpenSettings = onOpenSettings,
            )
        }
        state.statusMessage?.let { message ->
            StatusMessage(
                message = message,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

@Composable
private fun HtmlAppWideLayout(
    state: HtmlAppUiState,
    onSelectSession: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onComposerChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopStreaming: () -> Unit,
    onLoadMore: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        SidePanel(
            modifier = Modifier.width(300.dp),
            state = state,
            onSelectSession = onSelectSession,
            onSelectModel = onSelectModel,
            onNewChat = onNewChat,
            onOpenSettings = onOpenSettings,
        )
        Divider(color = Color(0xFF1F2937), modifier = Modifier.width(1.dp).fillMaxHeight())
        ChatSurface(
            modifier = Modifier.weight(1f),
            state = state,
            onSelectModel = onSelectModel,
            onComposerChange = onComposerChange,
            onSendMessage = onSendMessage,
            onStopStreaming = onStopStreaming,
            onLoadMore = onLoadMore,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun HtmlAppCompactLayout(
    state: HtmlAppUiState,
    onSelectSession: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onComposerChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopStreaming: () -> Unit,
    onLoadMore: () -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val drawerState = rememberModalNavDrawerState(
        initialValue = androidx.compose.material3.DrawerValue.Closed,
        confirmStateChange = { true },
    )
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerContent = {
            Surface(color = Color.Transparent) {
                SidePanel(
                    modifier = Modifier.width(300.dp),
                    state = state,
                    onSelectSession = {
                        onSelectSession(it)
                        scope.launch { drawerState.close() }
                    },
                    onSelectModel = onSelectModel,
                    onNewChat = onNewChat,
                    onOpenSettings = onOpenSettings,
                )
            }
        },
        drawerState = drawerState,
        scrimColor = Color(0xAA000000),
    ) {
        ChatSurface(
            modifier = Modifier.fillMaxSize(),
            state = state,
            onSelectModel = onSelectModel,
            onComposerChange = onComposerChange,
            onSendMessage = onSendMessage,
            onStopStreaming = onStopStreaming,
            onLoadMore = onLoadMore,
            onOpenSettings = {
                scope.launch { drawerState.close() }
                onOpenSettings()
            },
            onOpenDrawer = {
                scope.launch {
                    if (drawerState.isClosed) drawerState.open() else drawerState.close()
                }
            },
        )
    }
}

@Composable
private fun SidePanel(
    modifier: Modifier,
    state: HtmlAppUiState,
    onSelectSession: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PanelBrush)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ElevatedButton(
                    onClick = onNewChat,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.elevatedButtonColors(
                        containerColor = Color(0x3360A5FA),
                        contentColor = Color.White,
                    ),
                ) {
                    Text("新建对话", fontWeight = FontWeight.SemiBold)
                }
                ModelSelector(
                    models = state.availableModels,
                    selectedModelId = state.selectedModelId,
                    onSelectModel = onSelectModel,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Divider(color = Color(0x331F2937))
            Text(
                text = "对话记录",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF9CA3AF),
            )
            val sessions = state.sessions
            LazyColumn(
                modifier = Modifier.weight(1f, fill = true),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = { onSelectSession(session.id) },
                    )
                }
            }
            ElevatedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("设置")
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: ChatSessionUi,
    onClick: () -> Unit,
) {
    val borderColor = if (session.isActive) AccentBlue else Color(0x331F2937)
    val background = if (session.isActive) Color(0x144693FF) else Color(0x33212533)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .shadow(6.dp, RoundedCornerShape(18.dp), clip = false)
            .background(Color.Transparent)
            .clickable(onClick = onClick),
        color = background,
        tonalElevation = 1.dp,
        border = BorderStroke(1.2.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = session.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = session.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF9CA3AF),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModelSelector(
    models: List<ModelPresetUi>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x33212533))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        models.forEach { model ->
            val selected = model.id == selectedModelId
            val (background, foreground) = if (selected) {
                AccentBlue to Color.White
            } else {
                Color(0x1960A5FA) to Color(0xFF9CA3AF)
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelectModel(model.id) },
                color = background,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) AccentBlue else Color(0x331F2937),
                ),
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = foreground,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatSurface(
    modifier: Modifier,
    state: HtmlAppUiState,
    onSelectModel: (String) -> Unit,
    onComposerChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopStreaming: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()

    Scaffold(
        modifier = modifier.background(Color.Black),
        topBar = {
            ChatHeader(
                selectedModelId = state.selectedModelId,
                availableModels = state.availableModels,
                onSelectModel = onSelectModel,
                onOpenSettings = onOpenSettings,
                onOpenDrawer = onOpenDrawer,
            )
        },
        containerColor = Color.Transparent,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color.Black),
        ) {
            MessagesList(
                modifier = Modifier.weight(1f),
                messages = state.messages,
                canLoadMore = state.canLoadMore,
                onLoadMore = onLoadMore,
                listState = listState,
            )
            Composer(
                text = state.composerText,
                onTextChange = onComposerChange,
                onSend = onSendMessage,
                onStop = onStopStreaming,
                isSending = state.isSending,
                availableModels = state.availableModels,
                selectedModelId = state.selectedModelId,
                onSelectModel = onSelectModel,
            )
        }
    }
}

@Composable
private fun ChatHeader(
    selectedModelId: String,
    availableModels: List<ModelPresetUi>,
    onSelectModel: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: (() -> Unit)?,
) {
    Surface(
        color = Color.Transparent,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderBrush)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Filled.Menu, contentDescription = "菜单", tint = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Column {
                        val gradientTitle = remember {
                            buildAnnotatedString {
                                withStyle(
                                    SpanStyle(
                                        brush = Brush.linearGradient(listOf(AccentBlue, AccentGold)),
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                ) {
                                    append("AI Chat")
                                }
                            }
                        }
                        Text(
                            text = gradientTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.Unspecified,
                        )
                        Text(
                            text = "原生体验",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9CA3AF),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Surface(
                        shape = CircleShape,
                        color = Color(0x331F2937),
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            text = "v9.3",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9CA3AF),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModelSelector(
                        models = availableModels,
                        selectedModelId = selectedModelId,
                        onSelectModel = onSelectModel,
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 360.dp),
                    )
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "更多", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessagesList(
    modifier: Modifier,
    messages: List<ChatMessageUi>,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val lastMessageId = messages.lastOrNull()?.id
    LaunchedEffect(lastMessageId) {
        if (lastMessageId != null) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (canLoadMore) {
            item(key = "load-more") {
                LoadMoreHistory(onLoadMore)
            }
        }
        items(messages, key = { it.id }) { message ->
            MessageCard(message)
        }
    }
}

@Composable
private fun LoadMoreHistory(onLoadMore: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElevatedButton(
            onClick = onLoadMore,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.elevatedButtonColors(
                containerColor = Color(0x3360A5FA),
                contentColor = Color.White,
            ),
        ) {
            Text("加载更多历史")
        }
    }
}

@Composable
private fun SettingsDialog(
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成")
            }
        },
        title = { Text("设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "API Key",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF9CA3AF),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        placeholder = { Text("输入 OpenAI 或其他服务的 Key") },
                        singleLine = true,
                    )
                    Text(
                        text = "留空时将使用本地演示模型。",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF6B7280),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "数据备份",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF9CA3AF),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ElevatedButton(onClick = onExportBackup) {
                            Text("导出备份")
                        }
                        ElevatedButton(onClick = onImportBackup) {
                            Text("导入备份")
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun StatusMessage(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xCC1F2937),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 8.dp,
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun LaunchedStatusMessage(message: String?, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(3200)
            onDismiss()
        }
    }
}

@Composable
private fun MessageCard(message: ChatMessageUi) {
    val accent = when (message.role) {
        ChatRole.User -> AccentBlue
        ChatRole.Assistant -> Color(0xFF64D2FF)
    }
    val surfaceColor = Color(0xFF12151D)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = surfaceColor,
        ),
    ) {
        Column {
            MessageHeader(message, accent)
            Divider(color = Color(0x221F2937))
            Text(
                text = message.content,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                color = Color(0xFFE6E8EB),
                lineHeight = 20.sp,
            )
            message.thinking?.let {
                Text(
                    text = it,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x1460A5FA))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    color = AccentBlue,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun MessageHeader(message: ChatMessageUi, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x221F2937))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Text(
                text = if (message.role == ChatRole.User) "用户" else "助手",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            message.modelLabel?.let {
                Surface(
                    shape = CircleShape,
                    color = Color(0x2960A5FA),
                    border = BorderStroke(1.dp, AccentBlue),
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentBlue,
                    )
                }
            }
        }
        if (message.isStreaming) {
            Box(
                modifier = Modifier
                    .size(width = 2.dp, height = 18.dp)
                    .background(AccentBlue.copy(alpha = 0.8f)),
            )
        }
    }
}

@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isSending: Boolean,
    availableModels: List<ModelPresetUi>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HeaderBrush)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "发送到",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF9CA3AF),
            )
            ModelSelector(
                models = availableModels,
                selectedModelId = selectedModelId,
                onSelectModel = onSelectModel,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text("输入消息……", color = Color(0xFF9CA3AF)) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFFE6E8EB)),
            shape = RoundedCornerShape(18.dp),
            maxLines = 5,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ElevatedButton(
                onClick = onSend,
                enabled = text.isNotBlank() && !isSending,
                shape = RoundedCornerShape(18.dp),
            ) {
                if (isSending) {
                    Text("发送中…")
                } else {
                    Text("发送")
                }
            }
            ElevatedButton(
                onClick = onStop,
                enabled = isSending,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = DangerRed.copy(alpha = 0.9f),
                    contentColor = Color.White,
                ),
            ) {
                Text("停止")
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 411)
@Composable
fun PreviewHtmlAppScreen() {
    val sampleState = remember {
        HtmlAppUiState(
            sessions = List(6) { index ->
                ChatSessionUi(
                    id = index.toString(),
                    title = "会话 ${index + 1}",
                    subtitle = "最近更新 · ${index + 1} 分钟前",
                    isActive = index == 1,
                )
            },
            selectedSessionId = "1",
            availableModels = listOf(
                ModelPresetUi("gpt-4o", "GPT-4o"),
                ModelPresetUi("gpt-4o-mini", "GPT-4o mini"),
                ModelPresetUi("deepseek", "DeepSeek"),
            ),
            selectedModelId = "gpt-4o",
            messages = buildList {
                add(
                    ChatMessageUi(
                        id = UUID.randomUUID().toString(),
                        role = ChatRole.User,
                        content = "帮我分析一下这个 Compose 布局如何实现渐变背景和圆角阴影？",
                        modelLabel = "GPT-4o",
                    )
                )
                add(
                    ChatMessageUi(
                        id = UUID.randomUUID().toString(),
                        role = ChatRole.Assistant,
                        content = "可以使用 Brush.linearGradient 创建渐变 Brush，然后应用到 Modifier.background。圆角阴影可以通过 shadow 与 clip 配合实现。",
                        modelLabel = "GPT-4o",
                        thinking = "思考：需要检查阴影与背景叠加效果，推荐使用 Material3 Card。",
                    )
                )
            },
            composerText = "",
            isSending = false,
            canLoadMore = true,
            isSettingsVisible = true,
            statusMessage = "备份已导出",
            apiKey = "",
        )
    }
    HtmlAppScreen(
        state = sampleState,
        onSelectSession = {},
        onSelectModel = {},
        onComposerChange = {},
        onSendMessage = {},
        onStopStreaming = {},
        onLoadMore = {},
        onNewChat = {},
        onOpenSettings = {},
        onDismissSettings = {},
        onExportBackup = {},
        onImportBackup = {},
        onApiKeyChange = {},
        onDismissStatus = {},
    )
}
