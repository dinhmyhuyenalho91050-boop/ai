package com.example.htmlapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
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

    private val binder = LocalBinder()
    private val eventFlow = MutableSharedFlow<ServiceEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var currentEndpoint: String? = null
    private var triedConnecting = false
    private var isConnected = false
    @Volatile
    private var isStreamingActive = false
    @Volatile
    private var hasActiveClient = false
    private var lastMessageSnippet: String? = null
    private var currentNotificationText: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        currentNotificationText = getString(R.string.connection_status_connecting)
        startForeground(NOTIFICATION_ID, buildNotification(currentNotificationText))
        ensureConnection(DEFAULT_ENDPOINT)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val endpoint = intent?.getStringExtra(EXTRA_ENDPOINT) ?: currentEndpoint ?: DEFAULT_ENDPOINT
        if (intent?.hasExtra(EXTRA_STREAMING_ACTIVE) == true) {
            val active = intent.getBooleanExtra(EXTRA_STREAMING_ACTIVE, false)
            setStreamingActive(active)
        }
        ensureConnection(endpoint)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        hasActiveClient = true
        lastMessageSnippet = null
        updateNotification()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hasActiveClient = false
        updateNotification()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel()
        reconnectJob = null
        currentSocket?.cancel()
        currentSocket = null
        serviceScope.cancel()
        client.dispatcher.executorService.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun events(): SharedFlow<ServiceEvent> = eventFlow.asSharedFlow()

    fun setClientVisible(visible: Boolean) {
        hasActiveClient = visible
        if (visible) {
            lastMessageSnippet = null
        }
        updateNotification()
    }

    fun setStreamingActive(active: Boolean) {
        isStreamingActive = active
        updateNotification()
    }

    fun send(payload: String): Boolean {
        return currentSocket?.send(payload) == true
    }

    private fun ensureConnection(endpoint: String) {
        currentEndpoint = endpoint
        triedConnecting = true
        reconnectJob?.cancel()
        reconnectJob = null
        serviceScope.launch {
            connectSocket()
        }
    }

    private fun connectSocket() {
        val endpoint = currentEndpoint ?: return
        currentSocket?.cancel()
        currentSocket = client.newWebSocket(
            Request.Builder().url(endpoint).build(),
            socketListener
        )
        updateNotification()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(RETRY_DELAY_MS)
            connectSocket()
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            lastMessageSnippet = null
            pushEvent(ServiceEvent.ConnectionState(true))
            updateNotification()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (currentSocket === webSocket) {
                currentSocket = null
            }
            isConnected = false
            pushEvent(ServiceEvent.ConnectionState(false))
            updateNotification()
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (currentSocket === webSocket) {
                currentSocket = null
            }
            isConnected = false
            pushEvent(ServiceEvent.ConnectionState(false))
            pushEvent(ServiceEvent.Error(t))
            updateNotification()
            scheduleReconnect()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            pushEvent(ServiceEvent.Message(text))
            handleBackgroundMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val text = bytes.utf8()
            pushEvent(ServiceEvent.Message(text))
            handleBackgroundMessage(text)
        }
    }

    private fun handleBackgroundMessage(payload: String) {
        if (hasActiveClient) {
            return
        }
        val snippet = payload.replace("\n", " ").replace("\s+".toRegex(), " ").trim()
        if (snippet.isNotEmpty()) {
            lastMessageSnippet = snippet.take(60)
            updateNotification()
        }
    }

    private fun pushEvent(event: ServiceEvent) {
        eventFlow.tryEmit(event)
    }

    private fun updateNotification() {
        val statusText = resolveStatusText()
        if (statusText == currentNotificationText) {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(statusText)
            )
            return
        }
        currentNotificationText = statusText
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_ID,
            buildNotification(statusText)
        )
    }

    private fun resolveStatusText(): String {
        val base = when {
            isStreamingActive -> getString(R.string.connection_status_streaming)
            isConnected -> getString(R.string.connection_status_connected)
            triedConnecting && currentSocket == null -> getString(R.string.connection_status_disconnected)
            else -> getString(R.string.connection_status_connecting)
        }
        return if (!hasActiveClient && !lastMessageSnippet.isNullOrBlank()) {
            "$base · ${'$'}{lastMessageSnippet}"
        } else {
            base
        }
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, flags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setSmallIcon(R.drawable.ic_connection_status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.connection_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.connection_service_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }

    inner class LocalBinder : Binder() {
        fun getService(): ConnectionService = this@ConnectionService
    }

    sealed class ServiceEvent {
        data class Message(val payload: String) : ServiceEvent()
        data class ConnectionState(val connected: Boolean) : ServiceEvent()
        data class Error(val throwable: Throwable) : ServiceEvent()
    }

    companion object {
        const val EXTRA_ENDPOINT = "extra_connection_endpoint"
        const val EXTRA_STREAMING_ACTIVE = "extra_streaming_active"
        private const val CHANNEL_ID = "connection_service_channel"
        private const val NOTIFICATION_ID = 0x33
        private const val RETRY_DELAY_MS = 3_500L
        const val DEFAULT_ENDPOINT = "wss://example.com/stream"

        fun streamingStateIntent(context: Context, active: Boolean): Intent {
            return Intent(context, ConnectionService::class.java).apply {
                putExtra(EXTRA_STREAMING_ACTIVE, active)
            }
        }
    }
}
