package com.airi.vnetra.ui

/**
 * StreamActivity (UI layer for Sensor Data)
 *
 * Activity ini bertanggung jawab untuk menampilkan data stream secara real-time.
 * Data spasial dirender ke grid 8x8 dan status latensi sistem dipantau terus-menerus.
 * 
 * Aliran data murni menggunakan koneksi TCP/WebSocket ke ESP32 secara persisten.
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

import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airi.vnetra.databinding.ActivityStreamBinding
import com.airi.vnetra.service.StreamService
import com.airi.vnetra.util.SessionManager
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

    private var latencyMonitorJob: Job? = null

    // LatencyLogger telah dipindahkan ke StreamService agar tetap merekam di latar belakang.

    private lateinit var tofGridRenderer: ToFGridRenderer

    private var fpsWindowStart = 0L

    private var badgeSwipeRevealed = false

    @Volatile private var isAkhiring = false

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

        supportActionBar?.title = "VNetra Stream — $ipAddress"
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

        setupClickListeners()

        tofGridRenderer.initializeGrid()
        showStreamStateSafe(StreamState.CONNECTING)

        requestNotificationPermission()
        requestBatteryOptimizationBypass()

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

        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Menerima intent baru saat Activity sudah terbuka, untuk memperbarui alamat IP. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val newIp = intent?.getStringExtra(EXTRA_IP)
        if (!newIp.isNullOrEmpty() && newIp != ipAddress) {
            ipAddress = newIp
            supportActionBar?.title = "VNetra Stream — $ipAddress"
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

        setupBadgeSwipeGesture()

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
    private fun setupBadgeSwipeGesture() {
        val detector = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onFling(
                    e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    val diffX = e2.x - (e1?.x ?: e2.x)
                    return if (kotlin.math.abs(diffX) > 80f && kotlin.math.abs(velocityX) > 100f) {
                        badgeSwipeRevealed = !badgeSwipeRevealed
                        if (!isDestroyed && !isFinishing) {
                            android.transition.TransitionManager.beginDelayedTransition(
                                binding.badgeSwipeContainer,
                                android.transition.AutoTransition()
                            )
                            binding.btnAkhiriBadge.visibility =
                                if (badgeSwipeRevealed) android.view.View.VISIBLE else android.view.View.GONE
                            binding.tvConnectedBadge.text =
                                if (badgeSwipeRevealed) "● Terhubung  tutup »"
                                else "● Sensor VNetra Aktif  « geser"
                        }
                        true
                    } else false
                }
                override fun onDown(e: android.view.MotionEvent): Boolean = true
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

        latencyMonitorJob = lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                svc.latencyFlow.collect { metrics ->
                    if (isDestroyed || isFinishing || isAkhiring) return@collect
                    updateLatencyMonitorUi(metrics)
                }
            }
        }

        imuCollectJob = lifecycleScope.launch(Dispatchers.Default) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                try {
                    svc.imuFlow.collect { imuData ->
                        if (isDestroyed || isFinishing || isAkhiring) return@collect

                        latestImuData = imuData
                        lastImuReceivedAt = System.currentTimeMillis()
                        withContext(Dispatchers.Main) {
                            if (!isDestroyed && !isFinishing && !isAkhiring && imuData.size >= 6) {
                                val converged = imuData.getOrElse(8) { 0f } > 0.5f
                                if (converged) {
                                    binding.tvImuAccel.text = "Accel     : %6.2f m/s²".format(imuData[5])
                                } else {
                                    binding.tvImuAccel.text = "Mahony: warming up..."
                                }
                                binding.tvImuPitch.text     = "Pitch     : %5.1f°".format(imuData[0])
                                binding.tvImuRoll.text      = "Dyn Accel : %5.1f".format(imuData[1])
                                val pRate = imuData[3].let { if (kotlin.math.abs(it) < 4.0f) 0.0f else it }
                                val rRate = imuData[2].let { if (kotlin.math.abs(it) < 4.0f) 0.0f else it }
                                val yRate = imuData[4].let { if (kotlin.math.abs(it) < 4.0f) 0.0f else it }

                                binding.tvImuPitchRate.text = "Pitch Rate: %5.1f°/s".format(pRate)
                                binding.tvImuRollRate.text  = "Roll Rate : %5.1f°/s".format(rRate)
                                binding.tvImuYaw.text       = "Yaw Rate  : %5.1f°/s".format(yRate)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("StreamActivity", "IMU collect error", e)
                }
            }
        }

        tofCollectJob = lifecycleScope.launch(Dispatchers.Default) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                try {
                    svc.tofFlow.collect { tofData ->
                        if (isDestroyed || isFinishing || isAkhiring) return@collect
                        latestTofData = tofData
                        
                        processTofData(tofData)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("StreamActivity", "TOF collect error", e)
                }
            }
        }
    }

    private suspend fun processTofData(
        tofData: IntArray
    ) {
        if (cachedTofGridSize == 0 || tofData.size != cachedTofGridSize) {
            withContext(Dispatchers.Main) {
                if (::tofGridRenderer.isInitialized) {
                    val currentSize = tofGridRenderer.getGridSize()
                    if (tofData.size == currentSize) {
                        cachedTofGridSize = currentSize
                    }
                }
            }
            if (cachedTofGridSize == 0) return
        }

        val localSmoothed = com.airi.vnetra.util.SpatialMappingUtils.getSmoothedDistances()
        val localHoldover = com.airi.vnetra.util.SpatialMappingUtils.getHoldoverFrames()
        withContext(Dispatchers.Main) {
            if (!isDestroyed && !isFinishing && !isAkhiring && ::tofGridRenderer.isInitialized) {
                tofGridRenderer.updateGrid(
                    tofData = tofData,
                    smoothed = localSmoothed,
                    holdover = localHoldover
                )
            }
        }
    }


    /** Menampilkan popup peringatan konfirmasi sebelum pengguna memutus koneksi. */
    private fun konfirmasiAkhiriProses() {
        if (isDestroyed || isFinishing || isAkhiring) return
        AlertDialog.Builder(this)
            .setTitle("Akhiri Navigasi?")
            .setMessage("Apakah Anda ingin mematikan sensor dan keluar dari aplikasi, atau biarkan berjalan di latar belakang (Pocket Mode)?")
            .setPositiveButton("Keluar (Mati)") { _, _ -> akhiriProses() }
            .setNegativeButton("Latar Belakang") { _, _ -> moveTaskToBack(true) }
            .setNeutralButton("Batal") { dialog, _ -> dialog.dismiss() }
            .show()
    }


    /** Mengakhiri seluruh proses dan menutup koneksi secara aman. */
    private fun akhiriProses() {
        if (isAkhiring) return
        isAkhiring = true

        // Cetak ringkasan akhir sesi ke Logcat sebelum aplikasi ditutup (Dipindahkan ke StreamService)
        // latencyLogger.finalFlush()

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

    private fun showBadgeSafe() {
        if (isDestroyed || isFinishing) return
        badgeSwipeRevealed = false
        runCatching {
            binding.btnAkhiriBadge.visibility   = android.view.View.GONE
            binding.tvConnectedBadge.text       = "● Sensor VNetra Aktif  « geser"
            binding.tvConnectedBadge.visibility = android.view.View.VISIBLE
        }
    }

    private fun hideBadgeSafe() {
        if (isDestroyed || isFinishing) return
        badgeSwipeRevealed = false
        runCatching {
            binding.btnAkhiriBadge.visibility   = android.view.View.GONE
            binding.tvConnectedBadge.visibility = android.view.View.GONE
        }
    }

    /** Memperbarui status teks stream secara aman di main thread. */
    private fun showStreamStateSafe(state: StreamState) {
        if (isDestroyed || isFinishing) return
        runCatching {
            when (state) {
                StreamState.CONNECTING -> {
                    binding.progressStream.visibility = android.view.View.VISIBLE
                    binding.tvStreamStatus.text       = "Menunggu koneksi data spasial..."
                    binding.tvStreamStatus.visibility = android.view.View.VISIBLE
                    binding.btnReconnect.visibility   = View.GONE
                    binding.tvError.visibility        = View.GONE
                }
                StreamState.STREAMING -> {
                    binding.progressStream.visibility = View.GONE
                    binding.tvStreamStatus.visibility = View.GONE
                    binding.tvError.visibility        = View.GONE
                    binding.btnReconnect.visibility   = View.GONE
                }
                is StreamState.ERROR -> {
                    hideBadgeSafe()
                    binding.progressStream.visibility = android.view.View.GONE
                    binding.tvError.text              = state.message
                    binding.tvError.visibility        = android.view.View.VISIBLE
                    binding.btnReconnect.visibility   = android.view.View.VISIBLE
                    binding.tvStreamStatus.text       = "Offline — menunggu VNetra..."
                    binding.tvStreamStatus.visibility = android.view.View.VISIBLE
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
    }

    /** Memperbarui UI monitor latensi dan bottleneck. */
    private fun updateLatencyMonitorUi(metrics: com.airi.vnetra.util.LatencyMetrics) {
        if (isDestroyed || isFinishing) return
        
        val btValue = if (metrics.btPing > 0) "${metrics.btPing} ms" else "null"

        val text = """
            === LATENCY PING ===
            Sensor HW : ${metrics.hwPing} ms
            Serial    : ${metrics.serialPing} ms
            Algoritma : ${metrics.algoPing} ms
            Audio TTS : ${metrics.ttsPing} ms
            Bluetooth : $btValue
            ------------------------------
            TOTAL PING: ${metrics.totalPing} ms
            ==============================
        """.trimIndent()
        runCatching {
            binding.tvLatencyMonitor.text = text
        }
    }

    /** Menghapus tampilan visual jika data sensor sudah basi (stale). */
    private fun clearStaleSensorDisplay() {
        if (isDestroyed || isFinishing) return

        runOnUiThread {
            runCatching {
                binding.tvImuPitch.text = "Pitch: —"
                binding.tvImuRoll.text  = "Roll:  —"
                binding.tvImuYaw.text   = "Yaw:   —"
                binding.tvImuAccel.text = "Accel: —"
                binding.tvLatencyMonitor.text = "=== LATENCY PING ===\nSensor HW : —\nSerial    : —\nAlgoritma : —\nAudio TTS : —\nBluetooth : —\n------------------------------\nTOTAL PING: —\n=============================="
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
