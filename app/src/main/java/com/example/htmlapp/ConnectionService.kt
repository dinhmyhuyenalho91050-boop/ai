package com.example.htmlapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.BufferOverflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {

    data class ConnectionEvent(
        val type: String,
        val message: String? = null,
        val retryInMs: Long? = null
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val _events = MutableSharedFlow<ConnectionEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ConnectionEvent> = _events.asSharedFlow()

    private val isClientActive = MutableStateFlow(false)
    private val isStreaming = MutableStateFlow(false)
    private var webSocket: WebSocket? = null
    private var reconnectJobActive = false

    private val notificationManager by lazy {
        NotificationManagerCompat.from(this)
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): ConnectionService = this@ConnectionService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
        ensureConnected()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildStatusNotification(getString(R.string.connection_status_connecting)))
        ensureConnected()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UPDATE_STREAMING -> {
                val active = intent.getBooleanExtra(EXTRA_STREAMING_ACTIVE, false)
                updateStreaming(active)
            }
            ACTION_UPDATE_CLIENT_ACTIVE -> {
                val active = intent.getBooleanExtra(EXTRA_CLIENT_ACTIVE, false)
                updateClientActive(active)
            }
            ACTION_REFRESH -> ensureConnected(force = true)
            else -> ensureConnected()
        }
        return START_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        webSocket?.cancel()
        client.dispatcher.executorService.shutdown()
    }

    fun updateStreaming(active: Boolean) {
        isStreaming.value = active
        val statusText = if (active) {
            getString(R.string.connection_status_streaming)
        } else {
            getString(R.string.connection_status_standby)
        }
        updateForegroundStatus(statusText)
        emitEvent(
            ConnectionEvent(
                type = if (active) "streaming" else "idle"
            )
        )
    }

    fun updateClientActive(active: Boolean) {
        isClientActive.value = active
        if (active) {
            notificationManager.cancel(MESSAGE_NOTIFICATION_ID)
        }
    }

    fun refreshConnection() {
        ensureConnected(force = true)
    }

    private fun ensureConnected(force: Boolean = false) {
        if (!force && webSocket != null) {
            return
        }
        serviceScope.launch {
            reconnectJobActive = false
            webSocket?.cancel()
            emitEvent(ConnectionEvent(type = "connecting"))
            updateForegroundStatus(getString(R.string.connection_status_connecting))
            val url = DEFAULT_ENDPOINT
            try {
                val request = Request.Builder()
                    .url(url)
                    .build()
                webSocket = client.newWebSocket(request, Listener())
            } catch (e: Exception) {
                scheduleReconnect()
                emitEvent(ConnectionEvent(type = "error", message = e.localizedMessage))
                updateForegroundStatus(getString(R.string.connection_status_retry))
            }
        }
    }

    private fun scheduleReconnect(delayMs: Long = RECONNECT_DELAY_MS) {
        if (reconnectJobActive) return
        reconnectJobActive = true
        serviceScope.launch {
            emitEvent(ConnectionEvent(type = "retry", retryInMs = delayMs))
            updateForegroundStatus(getString(R.string.connection_status_retry))
            delay(delayMs)
            ensureConnected(force = true)
        }
    }

    private fun emitEvent(event: ConnectionEvent) {
        _events.tryEmit(event)
        if (!isClientActive.value && event.type == "message" && !event.message.isNullOrBlank()) {
            showMessageNotification(event.message)
        }
    }

    private fun updateForegroundStatus(status: String) {
        val notification = buildStatusNotification(status)
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun buildStatusNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.connection_service_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_connection_service)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun showMessageNotification(message: String) {
        val trimmed = if (message.length > 160) {
            message.substring(0, 157) + "…"
        } else {
            message
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.connection_service_message_title))
            .setContentText(trimmed)
            .setSmallIcon(R.drawable.ic_connection_service)
            .setStyle(NotificationCompat.BigTextStyle().bigText(trimmed))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(MESSAGE_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.connection_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.connection_channel_description)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            emitEvent(ConnectionEvent(type = "connected"))
            updateForegroundStatus(getString(R.string.connection_status_connected))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            emitEvent(ConnectionEvent(type = "message", message = text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            emitEvent(ConnectionEvent(type = "message", message = bytes.utf8()))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            emitEvent(ConnectionEvent(type = "closed", message = reason))
            updateForegroundStatus(getString(R.string.connection_status_retry))
            scheduleReconnect()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            emitEvent(ConnectionEvent(type = "error", message = t.localizedMessage ?: ""))
            updateForegroundStatus(getString(R.string.connection_status_retry))
            scheduleReconnect()
        }
    }

    companion object {
        private const val CHANNEL_ID = "connection_channel"
        private const val NOTIFICATION_ID = 44
        private const val MESSAGE_NOTIFICATION_ID = 45
        private const val RECONNECT_DELAY_MS = 10_000L
        private const val DEFAULT_ENDPOINT = "wss://example.com/events"

        const val ACTION_UPDATE_STREAMING = "com.example.htmlapp.service.ACTION_UPDATE_STREAMING"
        const val ACTION_UPDATE_CLIENT_ACTIVE = "com.example.htmlapp.service.ACTION_UPDATE_CLIENT_ACTIVE"
        const val ACTION_REFRESH = "com.example.htmlapp.service.ACTION_REFRESH"
        const val EXTRA_STREAMING_ACTIVE = "extra_streaming_active"
        const val EXTRA_CLIENT_ACTIVE = "extra_client_active"

        fun createStartIntent(context: Context): Intent =
            Intent(context, ConnectionService::class.java)

        fun createStreamingIntent(context: Context, active: Boolean): Intent =
            Intent(context, ConnectionService::class.java).apply {
                action = ACTION_UPDATE_STREAMING
                putExtra(EXTRA_STREAMING_ACTIVE, active)
            }

        fun createClientActiveIntent(context: Context, active: Boolean): Intent =
            Intent(context, ConnectionService::class.java).apply {
                action = ACTION_UPDATE_CLIENT_ACTIVE
                putExtra(EXTRA_CLIENT_ACTIVE, active)
            }
    }
}
