package com.example.htmlapp

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConnectionService : Service() {

    private val binder = ConnectionBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val localBroadcast by lazy { LocalBroadcastManager.getInstance(this) }
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }

    private var currentEndpoint: String = DEFAULT_ENDPOINT
    private var webSocket: WebSocket? = null
    private var reconnectJobActive = AtomicBoolean(false)
    private var clientVisible = AtomicBoolean(false)
    private var currentState: ConnectionState = ConnectionState.DISCONNECTED
    private var lastStatusEvent: ConnectionEvent.Status? = null

    private val _events = MutableSharedFlow<ConnectionEvent>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<ConnectionEvent> = _events

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        if (!startForegroundSafely(getString(R.string.service_initializing))) {
            stopSelf()
            return
        }
        ensureConnection()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onRebind(intent: Intent?) {
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                intent.getStringExtra(EXTRA_ENDPOINT)?.let { endpoint ->
                    if (endpoint.isNotBlank()) {
                        currentEndpoint = endpoint
                        restartConnection()
                    }
                }
                ensureConnection()
            }

            ACTION_SEND -> {
                val payload = intent.getStringExtra(EXTRA_PAYLOAD)
                if (!payload.isNullOrBlank()) {
                    sendMessage(payload)
                }
            }

            ACTION_VISIBILITY -> {
                val visible = intent.getBooleanExtra(EXTRA_VISIBLE, false)
                setClientVisibility(visible)
            }

            else -> ensureConnection()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        webSocket?.close(NORMAL_CLOSE_CODE, null)
        webSocket = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        ensureConnection()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        scheduleReconnect()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            scheduleReconnect()
        }
    }

    private fun ensureConnection() {
        if (webSocket == null) {
            connect()
        } else if (currentState == ConnectionState.DISCONNECTED) {
            restartConnection()
        } else {
            dispatchStatus(currentState)
        }
    }

    private fun connect() {
        dispatchStatus(ConnectionState.CONNECTING)
        val request = Request.Builder()
            .url(currentEndpoint)
            .build()
        webSocket = okHttpClient.newWebSocket(request, createListener())
        updateForegroundNotification(getString(R.string.service_connecting))
    }

    private fun restartConnection() {
        webSocket?.cancel()
        webSocket = null
        connect()
    }

    private fun scheduleReconnect() {
        if (reconnectJobActive.getAndSet(true)) {
            return
        }
        serviceScope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectJobActive.set(false)
            restartConnection()
        }
    }

    private fun createListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                currentState = ConnectionState.CONNECTED
                updateForegroundNotification(getString(R.string.service_connected))
                dispatchStatus(ConnectionState.CONNECTED)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingPayload(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingPayload(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                currentState = ConnectionState.DISCONNECTED
                dispatchStatus(ConnectionState.DISCONNECTED)
                updateForegroundNotification(getString(R.string.service_disconnected))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                currentState = ConnectionState.DISCONNECTED
                dispatchStatus(ConnectionState.DISCONNECTED)
                updateForegroundNotification(getString(R.string.service_disconnected))
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                currentState = ConnectionState.DISCONNECTED
                dispatchStatus(ConnectionState.DISCONNECTED)
                updateForegroundNotification(getString(R.string.service_retrying))
                dispatchEvent(ConnectionEvent.Error(t.message ?: "unknown error"))
                scheduleReconnect()
            }
        }
    }

    private fun handleIncomingPayload(payload: String) {
        dispatchEvent(ConnectionEvent.Message(payload))
        if (!clientVisible.get()) {
            showMessageNotification(payload)
        }
    }

    private fun dispatchStatus(state: ConnectionState) {
        currentState = state
        val status = ConnectionEvent.Status(state.label)
        lastStatusEvent = status
        dispatchEvent(status)
    }

    private fun dispatchEvent(event: ConnectionEvent) {
        _events.tryEmit(event)
        localBroadcast.sendBroadcast(Intent(ACTION_EVENT).apply {
            putExtra(EXTRA_EVENT_TYPE, event.type)
            event.payload?.let { putExtra(EXTRA_PAYLOAD, it) }
        })
    }

    fun setClientVisibility(visible: Boolean) {
        clientVisible.set(visible)
        if (visible) {
            NotificationManagerCompat.from(this).cancel(MESSAGE_NOTIFICATION_ID)
        }
    }

    fun getCurrentStatus(): ConnectionEvent.Status? = lastStatusEvent

    fun sendMessage(payload: String) {
        serviceScope.launch {
            if (webSocket == null) {
                withContext(Dispatchers.Main) { connect() }
            }
            if (webSocket?.send(payload) != true) {
                dispatchEvent(ConnectionEvent.Error("send_failed"))
                scheduleReconnect()
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val statusChannel = NotificationChannel(
            STATUS_CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_channel_description)
        }
        val messageChannel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            getString(R.string.service_message_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.service_message_channel_description)
        }
        manager.createNotificationChannel(statusChannel)
        manager.createNotificationChannel(messageChannel)
    }

    private fun buildStatusNotification(contentText: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)
        return NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateForegroundNotification(contentText: String) {
        if (!canPostNotifications()) return
        val notification = buildStatusNotification(contentText)
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun showMessageNotification(payload: String) {
        if (!canPostNotifications()) return
        val preview = if (payload.length > 120) payload.take(117) + "…" else payload
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 1, intent, pendingIntentFlags)
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(preview)
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(this).notify(MESSAGE_NOTIFICATION_ID, notification)
    }

    private fun startForegroundSafely(contentText: String): Boolean {
        return try {
            startForeground(NOTIFICATION_ID, buildStatusNotification(contentText))
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalStateException) {
            false
        }
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(this).areNotificationsEnabled()
        }
    }

    inner class ConnectionBinder : android.os.Binder() {
        fun getService(): ConnectionService = this@ConnectionService
    }

    enum class ConnectionState {
        CONNECTING,
        CONNECTED,
        DISCONNECTED;

        val label: String
            get() = when (this) {
                CONNECTING -> "connecting"
                CONNECTED -> "connected"
                DISCONNECTED -> "disconnected"
            }
    }

    sealed class ConnectionEvent(val type: String, val payload: String?) {
        class Message(payload: String) : ConnectionEvent(TYPE_MESSAGE, payload)
        class Status(payload: String) : ConnectionEvent(TYPE_STATUS, payload)
        class Error(payload: String) : ConnectionEvent(TYPE_ERROR, payload)

        companion object {
            const val TYPE_MESSAGE = "message"
            const val TYPE_STATUS = "status"
            const val TYPE_ERROR = "error"
        }
    }

    companion object {
        private const val TAG = "ConnectionService"
        private const val STATUS_CHANNEL_ID = "connection_service_status"
        private const val MESSAGE_CHANNEL_ID = "connection_service_messages"
        private const val NOTIFICATION_ID = 1001
        private const val MESSAGE_NOTIFICATION_ID = 2001
        private const val NORMAL_CLOSE_CODE = 1000
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val ACTION_CONNECT = "com.example.htmlapp.action.CONNECT"
        private const val ACTION_SEND = "com.example.htmlapp.action.SEND"
        private const val ACTION_VISIBILITY = "com.example.htmlapp.action.VISIBILITY"
        const val ACTION_EVENT = "com.example.htmlapp.action.EVENT"
        const val EXTRA_EVENT_TYPE = "extra_event_type"
        const val EXTRA_ENDPOINT = "extra_endpoint"
        const val EXTRA_PAYLOAD = "extra_payload"
        private const val EXTRA_VISIBLE = "extra_visible"
        const val DEFAULT_ENDPOINT = "wss://example.com/stream"

        fun startAndConnect(context: Context, endpoint: String? = null): Boolean {
            val intent = Intent(context, ConnectionService::class.java).apply {
                action = ACTION_CONNECT
                endpoint?.let { putExtra(EXTRA_ENDPOINT, it) }
            }
            return startForegroundServiceCompat(context, intent)
        }

        fun enqueueSend(context: Context, payload: String): Boolean {
            val intent = Intent(context, ConnectionService::class.java).apply {
                action = ACTION_SEND
                putExtra(EXTRA_PAYLOAD, payload)
            }
            return startForegroundServiceCompat(context, intent)
        }

        fun updateClientVisibility(context: Context, visible: Boolean): Boolean {
            val intent = Intent(context, ConnectionService::class.java).apply {
                action = ACTION_VISIBILITY
                putExtra(EXTRA_VISIBLE, visible)
            }
            return startForegroundServiceCompat(context, intent)
        }

        private fun startForegroundServiceCompat(context: Context, intent: Intent): Boolean {
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Unable to start foreground service", e)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "Unable to start foreground service", e)
                false
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Unable to start foreground service", e)
                false
            }
        }
    }
}
