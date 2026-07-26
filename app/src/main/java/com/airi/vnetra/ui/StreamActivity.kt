package com.airi.vnetra.ui

/**
 * StreamActivity (UI layer for Sensor Data)
 *
 * Activity utama yang menampilkan visualisasi data sensor ToF dan status MPU6050.
 * Menjaga koneksi ke layanan background (StreamService).
 * Catatan: Menggunakan nama 'Camera' untuk menjaga kompatibilitas intent dan manifest bawaan,
 * namun secara fungsional telah bertransformasi menjadi dashboard murni sensor spasial.
 */

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.lifecycle.lifecycleScope
import com.airi.vnetra.databinding.ActivityStreamBinding
import com.airi.vnetra.service.StreamService
import com.airi.vnetra.util.TofDepthEstimator
import com.airi.vnetra.util.TtsAlertManager
import com.airi.vnetra.util.SpatialMappingUtils
import com.airi.vnetra.util.SessionManager
import com.airi.vnetra.util.NavigationCoordinator
import com.airi.vnetra.util.ToFGridRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class StreamActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_IP = "esp32_ip"

        /** Membuat intent terkonfigurasi untuk berpindah ke Activity ini. */
        fun createIntent(context: Context, ipAddress: String): Intent =
            Intent(context, StreamActivity::class.java).apply {
                putExtra(EXTRA_IP, ipAddress)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }

    private lateinit var binding:        ActivityStreamBinding
    private lateinit var sessionManager: SessionManager

    private var streamService:   StreamService? = null
    private var isBound          = false
    private var stateCollectJob: Job? = null
    private var imuCollectJob:   Job? = null
    private var tofCollectJob:   Job? = null
    private var ipAddress:       String = ""

    private var currentTopInset = 0
    private var currentBottomInset = 0


    @Volatile private var latestImuData: FloatArray? = null
    @Volatile private var lastImuReceivedAt: Long = 0L
    @Volatile private var latestTofData: IntArray?   = null

    private val safeImuData: FloatArray?
        get() = if (System.currentTimeMillis() - lastImuReceivedAt > 200L) null else latestImuData

    @Volatile private var pingHardware: Long = 0
    @Volatile private var pingSerialTransmisi: Long = 0
    @Volatile private var pingAlgoritma: Long = 0
    @Volatile private var pingTts: Long = 0

    private var latencyMonitorJob: Job? = null
    private var muteToggleJob: Job? = null

    /**
     * LatencyLogger
     *
     * Mencatat seluruh nilai latensi ke Logcat dengan tag "LAT" setiap [LOG_INTERVAL_SEC] detik.
     * Gunakan perintah berikut untuk mengekspor log selama pengujian skripsi:
     *   adb logcat -s LAT > latency_sesi.txt
     * Data yang dicatat: Sensor, Serial, Algoritma Spasial, TTS, Bluetooth, dan Total E2E.
     */
    private inner class LatencyLogger {
        private val TAG = "LAT"
        private val LOG_INTERVAL_MS = 5_000L

        // Ring-buffer per metrik (maks 1000 sample per sesi)
        private val bufHardware  = ArrayDeque<Long>(1000)
        private val bufSerial    = ArrayDeque<Long>(1000)
        private val bufAlgo      = ArrayDeque<Long>(1000)
        private val bufTts       = ArrayDeque<Long>(1000)
        private val bufBt        = ArrayDeque<Long>(1000)
        private val bufTotal     = ArrayDeque<Long>(1000)

        private var lastLogTime  = 0L
        private var sampleCount  = 0

        private val sessionStart = System.currentTimeMillis()

        /** Dipanggil tiap frame — menyimpan sample latensi saat ini. */
        fun record(hw: Long, serial: Long, algo: Long, tts: Long, bt: Long, total: Long) {
            fun ArrayDeque<Long>.push(v: Long) { if (size >= 1000) removeFirst(); addLast(v) }
            bufHardware.push(hw)
            bufSerial.push(serial)
            bufAlgo.push(algo)
            bufTts.push(tts)
            bufBt.push(bt)
            bufTotal.push(total)
            sampleCount++

            val now = System.currentTimeMillis()
            if (now - lastLogTime >= LOG_INTERVAL_MS) {
                lastLogTime = now
                flush()
            }
        }

        /** Mencetak ringkasan statistik ke Logcat. */
        fun flush() {
            val elapsedSec = (System.currentTimeMillis() - sessionStart) / 1000
            Log.i(TAG, "===== LATENCY LOG [T+${elapsedSec}s | N=$sampleCount] =====")
            logStat("Sensor    ", bufHardware)
            logStat("Serial    ", bufSerial)
            logStat("Algoritma ", bufAlgo)
            logStat("TTS       ", bufTts)
            logStat("Bluetooth ", bufBt)
            logStat("TOTAL E2E ", bufTotal)
            Log.i(TAG, "========================================================")
        }

        private fun logStat(label: String, buf: ArrayDeque<Long>) {
            if (buf.isEmpty()) return
            val avg = buf.average().toLong()
            val min = buf.min()
            val max = buf.max()
            Log.i(TAG, "  $label | avg=${avg}ms  min=${min}ms  max=${max}ms")
        }

        /** Cetak ringkasan final saat sesi berakhir. */
        fun finalFlush() {
            Log.i(TAG, "===== SESI BERAKHIR — RINGKASAN AKHIR =====")
            flush()
        }
    }

    private val latencyLogger = LatencyLogger()

    private lateinit var ttsAlertManager: TtsAlertManager
    @Volatile private var initialYawOffset: Float? = null
    private lateinit var navigationCoordinator: NavigationCoordinator
    private lateinit var tofGridRenderer: ToFGridRenderer

    @Volatile private var isBlockedState = false

    private val HOLDOVER_FRAMES = 15

    private var frameCount     = 0
    private var fpsWindowStart = 0L

    private var badgeSwipeRevealed = false

    @Volatile private var isAkhiring = false

    // ponytail: cache BT status daripada query AudioManager 2x setiap 200ms
    @Volatile private var hasBluetooth: Boolean = false

    @Volatile private var cachedTofGridSize = 0

    private val exitReceiver = object : android.content.BroadcastReceiver() {
        /** Menerima pesan sistem (Broadcast) untuk memicu penutupan aplikasi secara penuh. */
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == StreamService.ACTION_EXIT_APP) {
                android.util.Log.d("StreamActivity", "Received exit broadcast from service, closing app")
                finishAffinity()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        /** Event saat Activity berhasil terhubung (binding) ke Service sensor. */
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (isDestroyed || isFinishing) return
            val binder = service as? StreamService.LocalBinder ?: return
            streamService = binder.getService()
            isBound       = true

            startObservingConnectionState()
        }

        /** Event saat Activity terputus (unbinding) dari Service sensor. */
        override fun onServiceDisconnected(name: ComponentName?) {

            streamService = null
            isBound       = false
            runOnUiThread {
                if (!isDestroyed && !isFinishing && !isAkhiring) {
                    showStreamStateSafe(StreamState.ERROR("Koneksi service terputus. Tekan Reconnect."))
                    hideBadgeSafe()
                }
            }
        }
    }

    /** Fungsi siklus hidup Android: dieksekusi saat komponen (Activity/Service) pertama kali dibuat. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)
        tofGridRenderer = ToFGridRenderer(this, binding.gridTof)

        setSupportActionBar(binding.toolbar)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ipAddress = intent.getStringExtra(EXTRA_IP) ?: run {
            Toast.makeText(this, "IP address tidak ditemukan", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        sessionManager = SessionManager(this)

        supportActionBar?.title = "Live Camera — $ipAddress"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            /** Menangani aksi tombol kembali, memunculkan dialog konfirmasi alih-alih langsung keluar. */
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            currentTopInset = systemBars.top
            currentBottomInset = systemBars.bottom

            binding.toolbar.setPadding(0, systemBars.top, 0, 0)

            updateUpperViewsMargins()

            binding.layoutControls.setPadding(
                binding.layoutControls.paddingLeft,
                binding.layoutControls.paddingTop,
                binding.layoutControls.paddingRight,
                systemBars.bottom + 16.dpToPx()
            )
            insets
        }

        setupBadgeSwipeGesture()
        setupClickListeners()

        tofGridRenderer.initializeGrid()
        showStreamStateSafe(StreamState.CONNECTING)

        requestNotificationPermission()
        requestBatteryOptimizationBypass()

        ttsAlertManager = TtsAlertManager(this)
        ttsAlertManager.initTts()
        navigationCoordinator = NavigationCoordinator()

    }

    /** Siklus hidup Android: dieksekusi saat antarmuka mulai tampil dan mengikat service. */
    override fun onStart() {
        super.onStart()

        val filter = android.content.IntentFilter(StreamService.ACTION_EXIT_APP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exitReceiver, filter)
        }

        if (ipAddress.isEmpty()) return
        val serviceIntent = StreamService.createStartIntent(this, ipAddress)
        startService(serviceIntent)

        if (!isBound) {
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /** Fungsi siklus hidup Android: dieksekusi saat antarmuka sudah tidak terlihat sama sekali. */
    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(exitReceiver) }

    }

    /** Dipanggil saat komponen dihancurkan; membersihkan resource. */
    override fun onDestroy() {
        super.onDestroy()

        cancelAllJobs()
        if (isBound) {
            runCatching { unbindService(serviceConnection) }
            isBound       = false
            streamService = null
        }

        if (::ttsAlertManager.isInitialized) ttsAlertManager.shutdown()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Menerima intent baru saat Activity sudah terbuka, untuk memperbarui alamat IP. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val newIp = intent?.getStringExtra(EXTRA_IP)
        if (!newIp.isNullOrEmpty() && newIp != ipAddress) {
            ipAddress = newIp
            supportActionBar?.title = "Live Camera — $ipAddress"
            val si = StreamService.createStartIntent(this, ipAddress)
            stopService(si); startService(si)
        }
    }

    /** Menangani logika tombol kembali pada action bar. */
    override fun onSupportNavigateUp(): Boolean { moveTaskToBack(true); return true }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {  }

    /** Meminta izin notifikasi kepada pengguna untuk streaming latar belakang (Android 13+). */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Meminta pengguna mengecualikan aplikasi dari optimasi baterai agar stream tidak dibunuh OS. */
    private fun requestBatteryOptimizationBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            }
        }
    }

    /** Menyiapkan aksi saat elemen antarmuka ditekan oleh pengguna. */
    private fun setupClickListeners() {

        binding.btnReconnect.setOnClickListener {
            if (isDestroyed || isFinishing) return@setOnClickListener
            showStreamStateSafe(StreamState.CONNECTING)
            hideBadgeSafe()
            val si = StreamService.createStartIntent(this, ipAddress)
            stopService(si)
            startService(si)
            if (!isBound) bindService(si, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        binding.btnAkhiri.setOnClickListener {
            if (!isDestroyed && !isFinishing) konfirmasiAkhiriProses()
        }

        binding.btnAkhiriBadge.setOnClickListener {
            if (!isDestroyed && !isFinishing) konfirmasiAkhiriProses()
        }
    }

    @Suppress("ClickableViewAccessibility")
    /** Menambahkan dukungan gestur usap (swipe) pada badge peringatan sensor. */
    private fun setupBadgeSwipeGesture() {
        val detector = GestureDetectorCompat(this,
            object : GestureDetector.SimpleOnGestureListener() {
                /** Mendeteksi gestur usapan cepat (fling) untuk menyembunyikan elemen UI tertentu. */
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    val diffX = e2.x - (e1?.x ?: e2.x)
                    return if (kotlin.math.abs(diffX) > 80f && kotlin.math.abs(velocityX) > 100f) {
                        badgeSwipeRevealed = !badgeSwipeRevealed
                        if (!isDestroyed && !isFinishing) {
                            binding.btnAkhiriBadge.visibility =
                                if (badgeSwipeRevealed) View.VISIBLE else View.GONE
                            binding.tvConnectedBadge.text =
                                if (badgeSwipeRevealed) "● Terhubung  ✕ tutup"
                                else "● Menerima data dari ESP32-S3  ‹ geser"
                        }
                        true
                    } else false
                }

                /** Menandakan bahwa sentuhan awal pada layar baru saja terjadi. */
                override fun onDown(e: MotionEvent): Boolean = true
            }
        )

        binding.badgeSwipeContainer.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
        binding.tvConnectedBadge.setOnTouchListener  { _, event -> detector.onTouchEvent(event) }
    }

    /** Memantau perubahan status koneksi (Flow) antara aplikasi dan ESP32 untuk pembaruan UI. */
    private fun startObservingConnectionState() {
        stateCollectJob?.cancel()
        stateCollectJob = lifecycleScope.launch {
            streamService?.connectionState?.collect { state ->
                if (isDestroyed || isFinishing || isAkhiring) return@collect
                when (state) {
                    StreamService.ConnectionState.CONNECTED -> {
                        showBadgeSafe()
                        showStreamStateSafe(StreamState.STREAMING)
                        startCollectingSensors()
                    }
                    StreamService.ConnectionState.CONNECTING -> {
                        hideBadgeSafe()
                        showStreamStateSafe(StreamState.CONNECTING)
                        clearStaleSensorDisplay()
                    }
                    StreamService.ConnectionState.DISCONNECTED -> {
                        hideBadgeSafe()
                    }
                }
            }
        }
    }



    /** Mengumpulkan aliran data IMU dari service latar belakang untuk koordinasi navigasi. */
    private fun startCollectingSensors() {
        val svc = streamService ?: return

        imuCollectJob?.cancel()
        tofCollectJob?.cancel()
        latencyMonitorJob?.cancel()
        muteToggleJob?.cancel()

        pingHardware = 0
        pingSerialTransmisi = 0
        pingAlgoritma = 0
        pingTts = 0

        latencyMonitorJob = lifecycleScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(200)
                updateLatencyMonitorUi()
            }
        }

        // Log periodik setiap 5 detik ke Logcat (tag: LAT)
        // Sekaligus update cache hasBluetooth sekali per iterasi
        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                kotlinx.coroutines.delay(200)
                hasBluetooth = run {
                    val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any {
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                }
                val btVal = if (hasBluetooth) 150L else 0L
                val total = pingHardware + pingSerialTransmisi + pingAlgoritma + pingTts + btVal
                latencyLogger.record(
                    hw     = pingHardware,
                    serial = pingSerialTransmisi,
                    algo   = pingAlgoritma,
                    tts    = pingTts,
                    bt     = btVal,
                    total  = total
                )
            }
        }



        muteToggleJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                svc.muteToggleFlow.collect {
                    if (::ttsAlertManager.isInitialized) {
                        ttsAlertManager.isMuted = !ttsAlertManager.isMuted
                        if (ttsAlertManager.isMuted) {
                            ttsAlertManager.speakForce("Suara dimatikan sementara")
                        } else {
                            ttsAlertManager.speakForce("Suara diaktifkan kembali")
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "Mute toggle collect error", e)
            }
        }

        imuCollectJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                svc.imuFlow.collect { imuData ->
                    if (isDestroyed || isFinishing || isAkhiring) return@collect

                    latestImuData = imuData
                    lastImuReceivedAt = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        if (!isDestroyed && !isFinishing && !isAkhiring && imuData.size >= 6) {
                            val converged = imuData.getOrElse(8) { 0f } > 0.5f
                            if (converged) {
                                if (initialYawOffset == null) {
                                    initialYawOffset = imuData[2]
                                }
                                binding.tvImuAccel.text = "Accel     : %6.2f m/s²".format(imuData[5])
                            } else {
                                binding.tvImuAccel.text = "Mahony: warming up..."
                            }
                            binding.tvImuPitch.text     = "Pitch     : %5.1f°".format(imuData[0])
                            binding.tvImuRoll.text      = "Roll      : %5.1f°".format(imuData[1])
                            binding.tvImuPitchRate.text = "Pitch Rate: %5.1f°/s".format(imuData[2])
                            binding.tvImuRollRate.text  = "Roll Rate : %5.1f°/s".format(imuData[3])
                            binding.tvImuYaw.text       = "Yaw Rate  : %5.1f°/s".format(imuData[4])
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "IMU collect error", e)
            }
        }

        tofCollectJob = lifecycleScope.launch(Dispatchers.Default) {
            var localSmoothed: FloatArray? = null
            var localHoldover: IntArray?   = null

            try {
                svc.tofFlow.collect { tofData ->
                    if (isDestroyed || isFinishing || isAkhiring) return@collect
                    latestTofData = tofData
                    
                    val (smoothed, holdover) = processTofData(tofData, localSmoothed, localHoldover)
                    localSmoothed = smoothed
                    localHoldover = holdover
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("StreamActivity", "TOF collect error", e)
            }
        }
    }

    /**
     * Memproses data ToF mentah, melakukan penghalusan (smoothing), dan mengevaluasi hambatan.
     * @param tofData Data mentah dari sensor ToF.
     * @param prevSmoothed Data halus dari frame sebelumnya.
     * @param prevHoldover Data penahan frame sebelumnya.
     * @return Pair berisi array data yang sudah dihaluskan dan array holdover terbaru.
     */
    private suspend fun processTofData(
        tofData: IntArray,
        prevSmoothed: FloatArray?,
        prevHoldover: IntArray?
    ): Pair<FloatArray, IntArray> {


        if (cachedTofGridSize == 0 || tofData.size != cachedTofGridSize) {
            withContext(Dispatchers.Main) {
                if (::tofGridRenderer.isInitialized) {
                    val currentSize = tofGridRenderer.getGridSize()
                    if (tofData.size != currentSize) {
                        val detectedMode = 8
                    } else {
                        cachedTofGridSize = currentSize
                    }
                }
            }

            if (cachedTofGridSize == 0) return Pair(FloatArray(0), IntArray(0))
        }

        val localSmoothed = if (prevSmoothed == null || prevSmoothed.size != tofData.size) FloatArray(tofData.size) { i -> tofData[i].toFloat() } else prevSmoothed
        val localHoldover = if (prevHoldover == null || prevHoldover.size != tofData.size) IntArray(tofData.size) { HOLDOVER_FRAMES } else prevHoldover

        val alpha = 0.3f

        withContext(Dispatchers.Main) {
            if (!isDestroyed && !isFinishing && !isAkhiring && ::tofGridRenderer.isInitialized) {
                tofGridRenderer.updateGrid(
                    tofData = tofData,
                                        smoothed = localSmoothed,
                    holdover = localHoldover,
                    alpha = alpha
                )
            }
        }


        evaluateObstacles(tofData)
        return Pair(localSmoothed, localHoldover)
    }

    /**
     * Mengevaluasi data hambatan berdasarkan jarak ToF dan orientasi IMU.
     * @param tofData Data jarak dari sensor ToF.
     */
    private fun evaluateObstacles(tofData: IntArray) {
        val imuSnap = safeImuData
        val rawTheta = imuSnap?.getOrElse(0) { 0f } ?: 0f
        val thetaDeg = rawTheta - 20f

        val startAlgo = System.currentTimeMillis()
        var closeThreatExists = false
        var allClear = true

        navigationCoordinator.updateMovementState(imuSnap)
        val isMovingForward = navigationCoordinator.movingForwardConsecutiveFrames >= 3
        val yawRate = imuSnap?.getOrElse(4) { 0f } ?: 0f
        val isTurning = kotlin.math.abs(yawRate) > 10f
        val isHeadRotating = navigationCoordinator.isHeadRotating(imuSnap, 15f)

        if (isMovingForward && ::ttsAlertManager.isInitialized && ttsAlertManager.isMuted) {
            ttsAlertManager.isMuted = false
            ttsAlertManager.speakForce("Pergerakan terdeteksi, suara diaktifkan kembali")
        }

        if (::ttsAlertManager.isInitialized) {
            val terrainAnalysis = SpatialMappingUtils.analyzeTerrain(tofData, thetaDeg)

            if (terrainAnalysis != null) {
                val obstacleAlert = ttsAlertManager.process(
                    trackingId = SpatialMappingUtils.WALL_TRACKING_ID,
                    dObj = terrainAnalysis.averageDistance,
                    clockDirection = terrainAnalysis.clockDirection,
                    objectLabel = terrainAnalysis.type,
                    isMovingForward = isMovingForward,
                    imuData = imuSnap
                )
                if (obstacleAlert != null) {
                    ttsAlertManager.speak(obstacleAlert)
                }
                
                val adaptiveT = if (isMovingForward) 1200 else 800
                if (terrainAnalysis.averageDistance < adaptiveT) {
                    closeThreatExists = true
                }
                if (terrainAnalysis.averageDistance < adaptiveT + TtsAlertManager.EPS_CLEAR_ZONE) {
                    allClear = false
                }
            } else {
                ttsAlertManager.process(
                    trackingId = SpatialMappingUtils.WALL_TRACKING_ID,
                    dObj = 2000,
                    clockDirection = 12,
                    objectLabel = "tembok",
                    isMovingForward = isMovingForward,
                    imuData = imuSnap
                )
            }

            val isDanger = closeThreatExists || !allClear
            ttsAlertManager.smartNavigation.processNavigationState(
                isDanger = isDanger,
                isMovingForward = isMovingForward,
                isTurning = isTurning,
                isHeadRotating = isHeadRotating
            )

            if (closeThreatExists) {
                isBlockedState = true
            } else if (allClear && isBlockedState) {
                isBlockedState = false
            }
        }
        pingAlgoritma = System.currentTimeMillis() - startAlgo
    }

    /** Menghitung dan memperbarui indikator kecepatan data (FPS) pada UI. */
    private fun updateFpsCounter(frameBytes: Int) {
        if (isDestroyed || isFinishing) return
        frameCount++
        val now     = System.currentTimeMillis()
        val elapsed = now - fpsWindowStart
        if (elapsed >= 1000) {
            runCatching {
                binding.tvStreamStatus.text =
                    "%.1f FPS  •  %d KB/frame".format(frameCount * 1000f / elapsed, frameBytes / 1024)
            }
            frameCount     = 0
            fpsWindowStart = now
        }
    }

    /** Menampilkan popup peringatan konfirmasi sebelum pengguna memutus koneksi. */
    private fun konfirmasiAkhiriProses() {
        if (isDestroyed || isFinishing || isAkhiring) return
        AlertDialog.Builder(this)
            .setTitle("Akhiri Proses")
            .setMessage("Yakin ingin menghentikan streaming dan menutup aplikasi?\n\nSaat dibuka kembali, aplikasi akan otomatis terhubung ke ESP32-S3.")
            .setPositiveButton("Akhiri") { _, _ -> akhiriProses() }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Mengakhiri seluruh proses dan menutup koneksi secara aman. */
    private fun akhiriProses() {
        if (isAkhiring) return
        isAkhiring = true

        // Cetak ringkasan akhir sesi ke Logcat sebelum aplikasi ditutup
        latencyLogger.finalFlush()

        cancelAllJobs()

        if (isBound) {
            runCatching { unbindService(serviceConnection) }
            isBound       = false
            streamService = null
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(StreamService.createStopIntent(this))
            } else {
                startService(StreamService.createStopIntent(this))
            }
        }

        Toast.makeText(this, "Aplikasi dihentikan. Buka kembali untuk terhubung.", Toast.LENGTH_SHORT).show()

        finishAffinity()
    }

    /** Menampilkan indikator UI secara aman di main thread. */
    private fun showBadgeSafe() {
        if (isDestroyed || isFinishing) return
        badgeSwipeRevealed = false
        runCatching {
            binding.btnAkhiriBadge.visibility   = View.GONE
            binding.tvConnectedBadge.text       = "● Menerima data dari ESP32-S3  ‹ geser"
            binding.tvConnectedBadge.visibility = View.VISIBLE
        }
    }

    /** Menyembunyikan indikator UI secara aman di main thread. */
    private fun hideBadgeSafe() {
        if (isDestroyed || isFinishing) return
        badgeSwipeRevealed = false
        runCatching {
            binding.btnAkhiriBadge.visibility   = View.GONE
            binding.tvConnectedBadge.visibility = View.GONE
        }
    }

    /** Memperbarui status teks stream secara aman di main thread. */
    private fun showStreamStateSafe(state: StreamState) {
        if (isDestroyed || isFinishing) return
        runCatching {
            when (state) {
                StreamState.CONNECTING -> {
                    binding.progressStream.visibility = View.VISIBLE
                    binding.tvStreamStatus.text       = "Menghubungkan ke sensor ESP32..."
                    binding.btnReconnect.visibility   = View.GONE
                    binding.tvError.visibility        = View.GONE
                }
                StreamState.STREAMING -> {
                    binding.progressStream.visibility = View.GONE
                    binding.tvError.visibility        = View.GONE
                    binding.btnReconnect.visibility   = View.GONE
                }
                is StreamState.ERROR -> {
                    binding.progressStream.visibility = View.GONE
                    binding.tvError.text              = state.message
                    binding.tvError.visibility        = View.VISIBLE
                    binding.btnReconnect.visibility   = View.VISIBLE
                    binding.tvStreamStatus.text       = "Offline — menunggu ESP32..."
                    hideBadgeSafe()
                }
            }
        }
    }

    /** Membatalkan seluruh coroutine/job yang sedang berjalan. */
    private fun cancelAllJobs() {
        runCatching { stateCollectJob?.cancel() };   stateCollectJob   = null
        runCatching { imuCollectJob?.cancel() };     imuCollectJob     = null
        runCatching { tofCollectJob?.cancel() };     tofCollectJob     = null
        runCatching { latencyMonitorJob?.cancel() }; latencyMonitorJob = null
        runCatching { muteToggleJob?.cancel() };     muteToggleJob     = null
    }

    /** Memperbarui UI monitor latensi dan bottleneck. */
    private fun updateLatencyMonitorUi() {
        if (isDestroyed || isFinishing) return
        
        val hardware = pingHardware
        val serial = pingSerialTransmisi
        val algo = pingAlgoritma
        val tts = pingTts
        
        val btLatency = if (hasBluetooth) 150L else 0L
        val btValue = if (hasBluetooth) "$btLatency ms" else "null"
        
        val totalPing = hardware + serial + algo + tts + btLatency

        val text = """
            === LATENCY MONITOR (SKRIPSI) ===
            Sensor Hardware   : $hardware ms
            Serial Transmisi  : $serial ms
            Algoritma Spasial : $algo ms
            Sintesis TTS      : $tts ms
            Earphone Bluetooth: $btValue
            ---------------------------------
            ► PING TOTAL      : $totalPing ms
            =================================
        """.trimIndent()
        runCatching {
            binding.tvLatencyMonitor.text = text
        }
    }

    /** Menghapus tampilan visual jika data sensor sudah basi (stale). */
    private fun clearStaleSensorDisplay() {
        if (isDestroyed || isFinishing) return

        if (::ttsAlertManager.isInitialized) {
            ttsAlertManager.stopSpeaking()
            ttsAlertManager.resetAllFlags()
        }
        isBlockedState = false
        initialYawOffset = null

        pingHardware = 0
        pingSerialTransmisi = 0
        pingAlgoritma = 0
        pingTts = 0

        runOnUiThread {
            runCatching {
                binding.tvImuPitch.text = "Pitch: —"
                binding.tvImuRoll.text  = "Roll:  —"
                binding.tvImuYaw.text   = "Yaw:   —"
                binding.tvImuAccel.text = "Accel: —"
                binding.tvLatencyMonitor.text = "=== SYSTEM PING MONITOR ===\nSensor Data : —\nToF Total  : —\n---------------------------\n► MAX BOTTLENECK : —\n\n[Sequential ToF Details]\n├─ Smoothing : —\n└─ Formula E/H : —\n==========================="
                if (::tofGridRenderer.isInitialized) {
                    tofGridRenderer.clearGrid()
                }
            }
        }
    }

    private var isFullscreen = false
    /** Mengubah mode tampilan UI menjadi layar penuh (fullscreen). */
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            supportActionBar?.hide()
        } else {
            supportActionBar?.show()
        }
        binding.root.post {
            updateUpperViewsMargins()
        }
    }

    private sealed class StreamState {
        object CONNECTING                      : StreamState()
        object STREAMING                       : StreamState()
        data class ERROR(val message: String) : StreamState()
    }


    /** Menyesuaikan margin UI bagian atas agar tidak tertutup notch/kamera. */
    private fun updateUpperViewsMargins() {
        if (!::binding.isInitialized) return
        val isToolbarVisible = binding.toolbar.visibility == View.VISIBLE
        val imuParams = binding.layoutImu.layoutParams as? FrameLayout.LayoutParams ?: return
        val latencyParams = binding.layoutLatencyMonitor.layoutParams as? FrameLayout.LayoutParams ?: return

        if (isToolbarVisible) {
            val actionBarHeight = getActionBarHeight()
            imuParams.topMargin = currentTopInset + actionBarHeight + 8.dpToPx()
            latencyParams.topMargin = currentTopInset + actionBarHeight + 8.dpToPx()
        } else {
            imuParams.topMargin = currentTopInset + 8.dpToPx()
            latencyParams.topMargin = currentTopInset + 8.dpToPx()
        }

        binding.layoutImu.layoutParams = imuParams
        binding.layoutLatencyMonitor.layoutParams = latencyParams
    }

    /** Mengambil tinggi ActionBar standar perangkat. */
    private fun getActionBarHeight(): Int {
        val tv = android.util.TypedValue()
        return if (theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            android.util.TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
        } else {
            56.dpToPx()
        }
    }

    /** Mengonversi ukuran dari Density-Independent Pixel (DP) ke Pixel (Px). */
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
