package com.airi.vnetra.service

/**
 * StreamService (Background Sensor Service)
 *
 * Mengelola koneksi persisten via WebSocket/HTTP ke perangkat ESP32.
 * Menjamin aliran data MPU6050 dan VL53L5CX tetap berjalan di background
 * bahkan ketika layar redup atau aplikasi di-minimize.
 * Menggunakan Foreground Service dengan notifikasi.
 */

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.airi.vnetra.R
import com.airi.vnetra.ui.StreamActivity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class StreamService : Service() {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        private const val TAG               = "StreamService"
        private const val NOTIF_CH_FG       = "stream_channel"
        private const val NOTIF_CH_ALERT    = "esp32_connected_alert"
        private const val NOTIF_ID_FG       = 1001
        private const val NOTIF_ID_ALERT    = 1002
        private const val NOTIF_ID_STOPPED  = 1003
        private const val FRAME_TYPE_IMU    = 0x02.toByte()
        private const val FRAME_TYPE_HBEAT  = 0x03.toByte()
        private const val FRAME_TYPE_TOF    = 0x04.toByte()
        private const val FRAME_HEADER_SZ   = 9
        private const val RECONNECT_BASE_MS = 1_000L
        private const val RECONNECT_MAX_MS  = 8_000L

        const val EXTRA_IP    = "esp32_ip"
        const val ACTION_STOP     = "com.airi.vnetra.ACTION_STOP"
        const val ACTION_EXIT_APP = "com.airi.vnetra.ACTION_EXIT_APP"

        /** Membuat konfigurasi Intent untuk memulai layanan (Service). */
        fun createStartIntent(ctx: Context, ip: String) =
            Intent(ctx, StreamService::class.java).apply { putExtra(EXTRA_IP, ip) }

        /** Membuat konfigurasi Intent untuk menghentikan layanan (Service). */
        fun createStopIntent(ctx: Context) =
            Intent(ctx, StreamService::class.java).apply { action = ACTION_STOP }
    }

    inner class LocalBinder : Binder() {
        /** Mengembalikan instance dari StreamService yang sedang berjalan. */
        fun getService(): StreamService = this@StreamService
    }

    private val binder       = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var streamJob: Job?             = null
    private var pingJob: Job?               = null
    private var watchdogJob: Job?           = null
    private var udpReceiverJob: Job?        = null
    private var activeUdpSocket: java.net.DatagramSocket? = null
    private var activeWebSocket: WebSocket? = null
    private var reconnectAttempts           = 0
    private var ipAddress                   = ""
    private var stopped                     = false
    private var lastDataReceivedTime        = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock?  = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // NONAKTIFKAN timeout baca searah untuk mencegah pemutusan saat HBEAT packet loss
        .pingInterval(5, TimeUnit.SECONDS) // Aktifkan Ping/Pong otomatis dua arah tiap 5 detik
        .build()

    private val _imuFlow = MutableSharedFlow<FloatArray>(
        replay              = 0,
        extraBufferCapacity = 4,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val imuFlow: SharedFlow<FloatArray> = _imuFlow

    private val _tofFlow = MutableSharedFlow<IntArray>(
        replay              = 0,
        extraBufferCapacity = 4,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )
    val tofFlow: SharedFlow<IntArray> = _tofFlow

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pingWebsocketFlow = MutableStateFlow(-1L)
    val pingWebsocketFlow: StateFlow<Long> = _pingWebsocketFlow.asStateFlow()

    private val _muteToggleFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val muteToggleFlow: SharedFlow<Unit> = _muteToggleFlow

    /** Mengikat komponen UI (Activity) ke komponen latar belakang (Service). */
    override fun onBind(intent: Intent?): IBinder = binder

    /** Merespons permintaan sistem Android untuk memulai atau melanjutkan Service. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "ACTION_STOP received")
            stopped = true

            runCatching {
                sendBroadcast(Intent(ACTION_EXIT_APP).apply {
                    setPackage(packageName)
                })
            }

            stopStreamAndRelease()
            cancelAllNotifications()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

            stopSelf()
            return START_NOT_STICKY
        }

        val ip = intent?.getStringExtra(EXTRA_IP)
        val ipChanged = !ip.isNullOrEmpty() && ip != ipAddress
        if (!ip.isNullOrEmpty()) ipAddress = ip

        if (ipAddress.isEmpty()) { stopSelf(); return START_NOT_STICKY }

        stopped = false

        createNotificationChannels()

        if (wakeLock?.isHeld != true) acquireWakeLock()
        if (wifiLock?.isHeld != true) acquireWifiLock()
        if (networkCallback == null) registerNetworkCallback()

        startForeground(NOTIF_ID_FG, buildForegroundNotif())

        val streamIsActive = streamJob?.isActive == true
        if (ipChanged || !streamIsActive) {
            Log.d(TAG, "Starting stream to $ipAddress (ipChanged=$ipChanged)")
            startStreaming(ipAddress)
        } else {
            Log.d(TAG, "Stream already active, skip restart")
        }

        return START_STICKY
    }

    /** Menghentikan koneksi stream, websocket, UDP, dan membebaskan resource (WakeLock/WifiLock). */
    fun stopStreamAndRelease() {
        runCatching { streamJob?.cancel() };       streamJob = null
        runCatching { pingJob?.cancel() };         pingJob = null
        runCatching { watchdogJob?.cancel() };     watchdogJob = null
        runCatching { udpReceiverJob?.cancel() };  udpReceiverJob = null
        runCatching { activeUdpSocket?.close() };  activeUdpSocket = null
        runCatching { activeWebSocket?.close(1000, "Stopped") }; activeWebSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        networkCallback?.let { cb ->
            runCatching {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
    }

    /** Menghapus semua notifikasi dari status bar Android. */
    private fun cancelAllNotifications() {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID_FG)
            nm.cancel(NOTIF_ID_ALERT)
        }
    }

    /** Memulai siklus koneksi UDP dan WebSocket ke ESP32, termasuk mekanisme auto-reconnect. */
    private fun startStreaming(ip: String) {
        streamJob?.cancel()
        watchdogJob?.cancel()
        udpReceiverJob?.cancel()
        runCatching { activeUdpSocket?.close() }
        activeUdpSocket   = null
        runCatching { activeWebSocket?.cancel() }
        activeWebSocket   = null
        reconnectAttempts = 0
        lastDataReceivedTime = System.currentTimeMillis()

        startUdpReceiver()

        watchdogJob = serviceScope.launch {
            while (isActive && !stopped) {
                delay(2000)
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    if (System.currentTimeMillis() - lastDataReceivedTime > 12_000L) {
                        Log.e(TAG, "Watchdog timeout: no data for >12s, canceling socket")
                        runCatching { activeWebSocket?.cancel() }
                        lastDataReceivedTime = System.currentTimeMillis()
                    }
                }
            }
        }

        streamJob = serviceScope.launch {
            while (isActive && !stopped) {
                val done = CompletableDeferred<Unit>()
                setConnectionState(ConnectionState.CONNECTING)
                Log.d(TAG, "Connecting ws://$ip/ws (attempt #${reconnectAttempts + 1})")

                try {
                    client.newWebSocket(
                        Request.Builder().url("ws://$ip/ws").build(),
                        object : WebSocketListener() {

                        /** Dijalankan saat koneksi WebSocket atau stream berhasil terbuka. */
                        override fun onOpen(ws: WebSocket, r: Response) {
                            runCatching {
                                activeWebSocket   = ws
                                reconnectAttempts = 0
                                setConnectionState(ConnectionState.CONNECTED)
                                sendConnectedHeadsUp()
                            }

                            pingJob?.cancel()
                            pingJob = serviceScope.launch {
                                while (isActive && activeWebSocket == ws) {
                                    runCatching { ws.send("PING:${System.currentTimeMillis()}") }
                                    delay(1000)
                                }
                            }
                        }

                        /** Dijalankan setiap kali menerima paket data baru (string/bytes) dari WebSocket. */
                        override fun onMessage(ws: WebSocket, text: String) {
                            if (stopped) return
                            if (text.startsWith("PONG:")) {
                                val sentTime = text.substringAfter("PONG:").toLongOrNull() ?: return
                                val rtt = System.currentTimeMillis() - sentTime

                                _pingWebsocketFlow.value = rtt / 2
                            } else if (text == "CMD:TOGGLE_MUTE") {
                                _muteToggleFlow.tryEmit(Unit)
                            }
                        }

                        /** Dijalankan setiap kali menerima paket data baru (string/bytes) dari WebSocket. */
                        override fun onMessage(ws: WebSocket, bytes: ByteString) {
                            if (stopped) return
                            lastDataReceivedTime = System.currentTimeMillis()
                            runCatching {
                                val raw = bytes.toByteArray()
                                if (raw.size < FRAME_HEADER_SZ) {
                                    Log.w(TAG, "Frame terlalu kecil: ${raw.size}B < $FRAME_HEADER_SZ")
                                    return
                                }
                                val type    = raw[0]
                                val payload = raw.copyOfRange(FRAME_HEADER_SZ, raw.size)

                                when (type) {
                                    FRAME_TYPE_IMU   -> {  }
                                    FRAME_TYPE_TOF   -> {  }
                                    FRAME_TYPE_HBEAT -> Log.d(TAG, "Heartbeat diterima")
                                    else -> Log.w(TAG, "Frame tidak dikenal: type=0x%02X size=${raw.size}B".format(type.toInt() and 0xFF))
                                }

                                when (type) {
                                    FRAME_TYPE_IMU  -> serviceScope.launch { emitImuPayload(payload) }
                                    FRAME_TYPE_TOF  -> serviceScope.launch { emitTofPayload(payload) }
                                }
                            }
                        }

                        /** Dijalankan saat koneksi WebSocket atau request mengalami kegagalan teknis. */
                        override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                            runCatching { pingJob?.cancel(); pingJob = null }
                            runCatching {
                                Log.e(TAG, "WS failure: ${t.message}")
                                activeWebSocket = null
                                setConnectionState(ConnectionState.DISCONNECTED)
                                if (!done.isCompleted) done.complete(Unit)
                            }
                        }

                        /** Menangani event penutupan WebSocket dari server secara bertahap. */
                        override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                            runCatching { ws.close(1000, null) }
                        }

                        /** Dijalankan saat koneksi WebSocket ditutup secara normal. */
                        override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                            runCatching { pingJob?.cancel(); pingJob = null }
                            runCatching {
                                activeWebSocket = null
                                setConnectionState(ConnectionState.DISCONNECTED)
                                if (!done.isCompleted) done.complete(Unit)
                            }
                        }
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "WS setup error: ${e.message}")
                    if (!done.isCompleted) done.complete(Unit)
                }

                try { done.await() }
                catch (e: CancellationException) {
                    runCatching { activeWebSocket?.close(1000, "Cancelled") }
                    break
                }

                if (!isActive || stopped) break

                val wait = minOf(
                    RECONNECT_BASE_MS * (1L shl reconnectAttempts.coerceAtMost(3)),
                    RECONNECT_MAX_MS
                )
                reconnectAttempts++
                Log.d(TAG, "Reconnect in ${wait}ms (attempt ${reconnectAttempts})")
                delay(wait)
            }
        }
    }

    /** Membuka DatagramSocket UDP pada port 8080 untuk menerima paket IMU dan ToF latensi rendah. */
    private fun startUdpReceiver() {
        udpReceiverJob?.cancel()
        runCatching { activeUdpSocket?.close() }
        activeUdpSocket = null

        udpReceiverJob = serviceScope.launch(Dispatchers.IO) {
            val socket = try {
                java.net.DatagramSocket(8080).apply {
                    reuseAddress = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal menginisialisasi DatagramSocket pada port 8080: ${e.message}")
                return@launch
            }
            activeUdpSocket = socket
            Log.d(TAG, "UDP Receiver started on port 8080")

            val buffer = ByteArray(256)
            val packet = java.net.DatagramPacket(buffer, buffer.size)

            try {
                while (isActive && !stopped) {
                    packet.length = buffer.size
                    socket.receive(packet)
                    if (stopped) break

                    lastDataReceivedTime = System.currentTimeMillis()

                    val len = packet.length
                    if (len < FRAME_HEADER_SZ) {
                        continue
                    }

                    val raw = packet.data.copyOfRange(packet.offset, packet.offset + len)
                    val type = raw[0]
                    val payload = raw.copyOfRange(FRAME_HEADER_SZ, raw.size)

                    when (type) {
                        FRAME_TYPE_IMU -> {
                            emitImuPayload(payload)
                        }
                        FRAME_TYPE_TOF -> {
                            emitTofPayload(payload)
                        }
                    }
                }
            } catch (e: java.net.SocketException) {
                Log.d(TAG, "UDP Socket closed or interrupted: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error di UDP Receiver: ${e.message}")
            } finally {
                runCatching { socket.close() }
                if (activeUdpSocket === socket) {
                    activeUdpSocket = null
                }
                Log.d(TAG, "UDP Receiver stopped")
            }
        }
    }

    /** Mengirimkan perintah perubahan resolusi matriks ToF ke ESP32 (4x4 atau 8x8). */
    fun sendTofModeCommand(resolution: Int) {
        val cmd = when (resolution) {
            4 -> "SET_TOF_MODE:4"
            8 -> "SET_TOF_MODE:8"
            else -> return
        }
        runCatching {
            activeWebSocket?.send(cmd)
            Log.d(TAG, "Sent TOF mode command: $cmd")
        }.onFailure {
            Log.e(TAG, "Failed to send TOF mode command: ${it.message}")
        }
    }

    /** Fungsi pembantu untuk mengirim perintah string kustom via WebSocket. */
    fun sendCustomCommand(cmd: String) {
        runCatching {
            activeWebSocket?.send(cmd)
            Log.d(TAG, "Sent custom command: $cmd")
        }.onFailure {
            Log.e(TAG, "Failed to send custom command: ${it.message}")
        }
    }

    /** Mendapatkan WakeLock Android agar CPU tetap aktif saat streaming background. */
    private fun acquireWakeLock() {
        runCatching {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraStream::WakeLock")
                .apply { acquire(12 * 60 * 60 * 1000L) }
        }.onFailure { Log.e(TAG, "WakeLock failed: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    /** Mendapatkan WifiLock Android agar koneksi WiFi stabil dan tidak masuk mode power-saving. */
    private fun acquireWifiLock() {
        runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "CameraStream::WifiLock")
                .apply { setReferenceCounted(false); acquire() }
        }.onFailure { Log.e(TAG, "WifiLock failed: ${it.message}") }
    }

    /** Mendaftarkan callback untuk memantau perubahan status ketersediaan koneksi internet/WiFi. */
    private fun registerNetworkCallback() {
        runCatching {
            val cm  = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                /** Event saat koneksi jaringan kembali tersedia (memicu reconnect). */
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available")

                    if (!stopped && streamJob?.isActive != true && ipAddress.isNotEmpty()) {
                        serviceScope.launch { delay(500); startStreaming(ipAddress) }
                    }
                }
                /** Event saat koneksi jaringan terputus (memperbarui state ke DISCONNECTED). */
                override fun onLost(network: Network) {
                    Log.w(TAG, "Network lost")
                    runCatching { activeWebSocket?.cancel() }
                    activeWebSocket = null
                    setConnectionState(ConnectionState.DISCONNECTED)
                }
            }
            networkCallback?.let { cm.registerNetworkCallback(req, it) }
        }.onFailure { Log.e(TAG, "NetworkCallback failed: ${it.message}") }
    }

    /** Memperbarui state koneksi (Flow) dan me-refresh notifikasi. */
    private fun setConnectionState(state: ConnectionState) {
        _connectionState.value = state
        runCatching {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID_FG, buildForegroundNotif())
        }
    }

    /** Mengirim notifikasi prioritas tinggi (heads-up) saat ESP32 berhasil terkoneksi. */
    private fun sendConnectedHeadsUp() {
        runCatching {
            val pi = PendingIntent.getActivity(
                this, 0,
                StreamActivity.createIntent(this, ipAddress),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, NOTIF_CH_ALERT)
                .setContentTitle("ESP32-S3 Terkoneksi 🟢")
                .setContentText("Streaming sensor aktif ($ipAddress)")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID_ALERT, notif)
        }
    }

    /** Membangun objek Notifikasi Android untuk Service Foreground yang berjalan terus-menerus. */
    private fun buildForegroundNotif(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            StreamActivity.createIntent(this, ipAddress),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            createStopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val (title, text) = when (_connectionState.value) {
            ConnectionState.CONNECTED    -> "ESP32-S3 Terkoneksi"  to "Streaming sensor aktif ($ipAddress)"
            ConnectionState.CONNECTING   -> "ESP32-S3 Camera"      to "Menghubungkan ke $ipAddress..."
            ConnectionState.DISCONNECTED -> "ESP32-S3 Camera"      to "Terputus — mencoba reconnect..."
        }

        return NotificationCompat.Builder(this, NOTIF_CH_FG)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Akhiri", stopPi)
            .build()
    }

    /** Mendaftarkan Notification Channel (wajib di Android 8.0+) untuk service dan alert. */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            NOTIF_CH_FG, "Camera Stream Status", NotificationManager.IMPORTANCE_LOW
        ).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(
            NOTIF_CH_ALERT, "ESP32 Connection Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply { enableVibration(true) })
    }

    /** Dijalankan saat pengguna menggeser aplikasi dari Recent Apps (menutup service dengan bersih). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        Log.d(TAG, "Task removed — membunuh service secara bersih")
        stopStreamAndRelease()
        cancelAllNotifications()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Mem-parsing byte array payload IMU (pitch, roll, yaw, dll) dan memancarkannya via Flow. */
    private suspend fun emitImuPayload(payload: ByteArray) {
        when {
            payload.size >= 36 -> {
                val floats = FloatArray(9)
                java.nio.ByteBuffer.wrap(payload)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer().get(floats)
                _imuFlow.emit(floats)
            }
            payload.size >= 24 -> {
                val floats = FloatArray(9)
                java.nio.ByteBuffer.wrap(payload, 0, 24)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asFloatBuffer().get(floats, 0, 6)
                _imuFlow.emit(floats)
            }
            else -> Log.w(TAG, "IMU payload terlalu kecil: ${payload.size}B (min 24B diperlukan)")
        }
    }

    /** Mem-parsing payload sensor ToF menjadi matriks jarak (int) dan memancarkannya via Flow. */
    private suspend fun emitTofPayload(payload: ByteArray) {
        if (payload.size >= 2) {
            val resMode = payload[0].toInt() and 0xFF
            val numCells = resMode * resMode
            val distSize = numCells * 2

            if (payload.size >= 1 + distSize) {
                val ints = IntArray(numCells)
                val buf = java.nio.ByteBuffer.wrap(payload, 1, distSize)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer()

                for (i in 0 until numCells) {
                    ints[i] = buf.get(i).toInt()
                }
                _tofFlow.emit(ints)
            } else {
                Log.e(TAG, "TOF payload terlalu kecil untuk ${resMode}x${resMode}: ${payload.size}B < ${1 + distSize}B")
            }
        } else {
            Log.e(TAG, "TOF payload terlalu kecil: ${payload.size}B < 2B!")
        }
    }

    /** Dipanggil saat komponen dihancurkan; membersihkan resource. */
    override fun onDestroy() {
        super.onDestroy()
        stopStreamAndRelease()
        cancelAllNotifications()
        runCatching { serviceScope.cancel() }
    }
}
