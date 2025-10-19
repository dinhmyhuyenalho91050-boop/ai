package com.example.htmlapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): ConnectionService = this@ConnectionService
    }

    enum class EventType {
        CONNECTING,
        CONNECTED,
        MESSAGE,
        DISCONNECTED,
        ERROR
    }

    data class ConnectionEvent(val type: EventType, val payload: String? = null)

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val _events = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ConnectionEvent> = _events

    private var currentUrl: String = DEFAULT_ENDPOINT
    private var reconnectJob: Job? = null
    private var webSocket: WebSocket? = null
    private var foregroundStarted = false
    @Volatile
    private var hasActiveClient = false
    private var lastNotificationState: String = "保持连接中…"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(EXTRA_ENDPOINT)?.takeIf { it.isNotBlank() }
        if (target != null && target != currentUrl) {
            currentUrl = target
            stopConnection()
        }
        ensureForegroundNotification(lastNotificationState)
        startConnection()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        hasActiveClient = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopConnection()
        serviceScope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    fun setClientActive(active: Boolean) {
        hasActiveClient = active
        if (active) {
            ensureForegroundNotification("前台保持连接")
        }
    }

    @Synchronized
    fun startConnection(endpoint: String? = null) {
        endpoint?.let {
            if (it != currentUrl) {
                currentUrl = it
                stopConnection()
            }
        }
        if (reconnectJob?.isActive == true) {
            return
        }
        reconnectJob = serviceScope.launch {
            var backoff = INITIAL_BACKOFF
            while (isActive) {
                publish(ConnectionEvent(EventType.CONNECTING))
                ensureForegroundNotification("正在连接服务…")
                val closeSignal = CompletableDeferred<Unit>()
                val listener = ConnectionListener(closeSignal) {
                    backoff = INITIAL_BACKOFF
                }
                val request = Request.Builder().url(currentUrl).build()
                webSocket = client.newWebSocket(request, listener)
                closeSignal.await()
                if (!isActive) break
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF)
                delay(backoff)
            }
        }
    }

    @Synchronized
    fun stopConnection() {
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(CLOSE_NORMAL, "client stopped")
        webSocket = null
    }

    fun sendMessage(payload: String): Boolean {
        return webSocket?.send(payload) == true
    }

    private fun publish(event: ConnectionEvent) {
        _events.tryEmit(event)
    }

    private fun ensureForegroundNotification(content: String) {
        lastNotificationState = content
        mainHandler.post {
            val manager = getSystemService(NotificationManager::class.java)
            val notification = buildNotification(content)
            if (!foregroundStarted) {
                startForeground(NOTIFICATION_ID, notification)
                foregroundStarted = true
            } else {
                manager?.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name) + " 通知",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持与后端的连接状态"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private inner class ConnectionListener(
        private val closeSignal: CompletableDeferred<Unit>,
        private val onConnected: () -> Unit
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            onConnected()
            publish(ConnectionEvent(EventType.CONNECTED))
            ensureForegroundNotification("连接已建立")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            publish(ConnectionEvent(EventType.MESSAGE, text))
            if (!hasActiveClient) {
                ensureForegroundNotification("收到新消息")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            publish(ConnectionEvent(EventType.MESSAGE, bytes.utf8()))
            if (!hasActiveClient) {
                ensureForegroundNotification("收到新消息")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            publish(ConnectionEvent(EventType.DISCONNECTED, reason))
            closeSignal.complete(Unit)
            ensureForegroundNotification("连接断开，准备重试…")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            publish(ConnectionEvent(EventType.ERROR, t.message))
            if (!closeSignal.isCompleted) {
                closeSignal.complete(Unit)
            }
            ensureForegroundNotification("连接异常，正在重试…")
        }
    }

    companion object {
        private const val CHANNEL_ID = "connection_channel"
        private const val NOTIFICATION_ID = 101
        private const val DEFAULT_ENDPOINT = "wss://example.com/ws"
        private const val INITIAL_BACKOFF = 2_000L
        private const val MAX_BACKOFF = 30_000L
        private const val CLOSE_NORMAL = 1000
        const val EXTRA_ENDPOINT = "extra_connection_endpoint"
    }
}
