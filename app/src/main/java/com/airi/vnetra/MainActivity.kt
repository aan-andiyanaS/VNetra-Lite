package com.airi.vnetra

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airi.vnetra.ble.BleManager
import com.airi.vnetra.databinding.ActivityMainBinding
import com.airi.vnetra.databinding.ItemDeviceBinding
import com.airi.vnetra.ui.StreamActivity
import com.airi.vnetra.ui.DeviceConfigActivity
import com.airi.vnetra.util.SessionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : AppCompatActivity() {

    private lateinit var binding:        ActivityMainBinding
    private lateinit var sessionManager: SessionManager
    private var bleManager: BleManager? = null
    private var deviceAdapter: DeviceAdapter? = null
    private var autoConnectJob: kotlinx.coroutines.Job? = null

    private val stopScanRunnable = Runnable {
        bleManager?.takeIf { it.isScanning.value }?.stopScan()
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            if (bleManager?.isBluetoothEnabled() == false) {
                @Suppress("DEPRECATION")
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                startScan()
            }
        } else {
            Toast.makeText(this, "Izin Bluetooth diperlukan untuk scan perangkat", Toast.LENGTH_SHORT).show()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) checkPermissionsAndScan()
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                16.dpToPx(),
                systemBars.top + 16.dpToPx(),
                16.dpToPx(),
                systemBars.bottom + 16.dpToPx()
            )
            insets
        }

        sessionManager = SessionManager(this)

        bleManager = BleManager(this)
        @android.annotation.SuppressLint("MissingPermission")
          val adapter = DeviceAdapter { scanResult ->
            startActivity(Intent(this, DeviceConfigActivity::class.java).apply {
                putExtra("device_name",    if (hasBleConnectPermission()) scanResult.device.name ?: "Unknown" else "Unknown")
                putExtra("device_address", scanResult.device.address)
            })
        }
        deviceAdapter = adapter

        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter  = adapter
        }

        binding.layoutBanner.visibility = View.GONE

        setupClickListeners()
        observeState()

        checkBluetoothAndScan()

        val savedIp = sessionManager.getSavedEsp32Ip()
        if (savedIp != null) {
            startAutoConnectCheck(savedIp)
        }
    }

    /** Menyiapkan aksi saat elemen antarmuka ditekan oleh pengguna. */
    private fun setupClickListeners() {
        binding.btnScan.setOnClickListener {
            val bm = bleManager ?: return@setOnClickListener
            if (bm.isScanning.value) bm.stopScan()
            else checkBluetoothAndScan()
        }
    }

    /** Memantau perubahan state dari ViewModel atau Flow untuk memperbarui UI. */
    private fun observeState() {
        val bm = bleManager ?: return

        lifecycleScope.launch {
            bm.isScanning.collectLatest { isScanning ->
                binding.btnScan.text           = if (isScanning) "Stop Scan" else "Scan ESP32"
                binding.progressBar.visibility = if (isScanning) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            bm.scanResults.collectLatest { results ->
                deviceAdapter?.submitList(results)
                binding.tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
                if (results.isEmpty()) {
                    binding.tvEmpty.text = "Tidak ada perangkat ESP32 ditemukan.\nPastikan ESP32 menyala dan dalam mode BLE."
                }
            }
        }
    }

    /** Menjalankan operasi logika spesifik fungsi ini. */
    private fun checkBluetoothAndScan() {
        val bm = bleManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
            return
        }
        if (!bm.isBluetoothEnabled()) {
            @Suppress("DEPRECATION")
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            checkPermissionsAndScan()
        }
    }

    /** Menjalankan operasi logika spesifik fungsi ini. */
    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isEmpty()) startScan()
        else permissionLauncher.launch(notGranted.toTypedArray())
    }

    /** Menjalankan operasi logika spesifik fungsi ini. */
    private fun startScan() {
        bleManager?.startScan()
        binding.root.removeCallbacks(stopScanRunnable)
        binding.root.postDelayed(stopScanRunnable, 15_000)
    }

    /** Dipanggil saat komponen dihancurkan; membersihkan resource. */
    override fun onDestroy() {
        super.onDestroy()
        autoConnectJob?.cancel()
        binding.root.removeCallbacks(stopScanRunnable)
        bleManager?.close()
    }

    /** Menjalankan operasi logika spesifik fungsi ini. */
    private fun startAutoConnectCheck(ipAddress: String) {
        autoConnectJob?.cancel()

        binding.layoutBanner.visibility = View.VISIBLE
        binding.tvBannerMessage.text = "Menghubungkan ke perangkat tersimpan ($ipAddress)..."
        binding.btnConnectDevice.text = "Batal"

        binding.btnConnectDevice.setOnClickListener {
            autoConnectJob?.cancel()
            binding.layoutBanner.visibility = View.GONE
            Toast.makeText(this, "Pencarian otomatis dibatalkan.", Toast.LENGTH_SHORT).show()
        }

        autoConnectJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                var socket: Socket? = null
                val isOnline = try {
                    socket = Socket()
                    socket.connect(InetSocketAddress(ipAddress, 80), 1000)
                    true
                } catch (e: Exception) {
                    false
                } finally {
                    runCatching { socket?.close() }
                }

                if (isOnline) {
                    withContext(Dispatchers.Main) {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@MainActivity, "Terhubung ke ESP32 ($ipAddress)", Toast.LENGTH_SHORT).show()
                            startActivity(StreamActivity.createIntent(this@MainActivity, ipAddress))
                            finish()
                        }
                    }
                    break
                }

                delay(3000)
            }
        }
    }

    inner class DeviceAdapter(
        private val onClick: (ScanResult) -> Unit
    ) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

        private var items: List<ScanResult> = emptyList()

        /** Mengirimkan daftar data terbaru ke Adapter untuk dirender ulang. */
        fun submitList(newItems: List<ScanResult>) {
            items = newItems
            notifyDataSetChanged()
        }

        /** Membuat dan menginisialisasi ViewHolder untuk item daftar. */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        /** Mengisi data spesifik ke dalam elemen tampilan pada posisi tertentu. */
        override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
        /** Mengembalikan jumlah total item dalam daftar RecyclerView. */
        override fun getItemCount() = items.size

        inner class ViewHolder(private val b: ItemDeviceBinding) : RecyclerView.ViewHolder(b.root) {
            /** Mengikat data spesifik ke dalam elemen tampilan individual. */
            @android.annotation.SuppressLint("MissingPermission")
              fun bind(sr: ScanResult) {
                b.tvDeviceName.text    = if (hasBleConnectPermission()) sr.device.name ?: "Unknown Device" else "Unknown Device"
                b.tvDeviceAddress.text = sr.device.address
                b.tvRssi.text          = "${sr.rssi} dBm"
                b.root.setOnClickListener { onClick(sr) }
            }
        }
    }

    /** Mengonversi ukuran dari Density-Independent Pixel (DP) ke Pixel (Px). */
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun hasBleConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
