package com.airi.vnetra.ui

/**
 * DeviceConfigActivity
 *
 * Titik masuk (entry point) aplikasi untuk penemuan perangkat keras (ESP32).
 * Memindai IP lokal atau menggunakan mDNS untuk menemukan server ESP32 di jaringan,
 * lalu meluncurkan dashboard navigasi jika berhasil.
 */

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airi.vnetra.R
import com.airi.vnetra.ble.BleManager
import com.airi.vnetra.databinding.ActivityDeviceConfigBinding
import com.airi.vnetra.databinding.DialogWifiPasswordBinding
import com.airi.vnetra.databinding.ItemWifiBinding
import com.airi.vnetra.model.WifiInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.airi.vnetra.util.SessionManager
import android.os.Build
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

@SuppressLint("MissingPermission")
class DeviceConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceConfigBinding
    private lateinit var bleManager: BleManager
    private lateinit var wifiAdapter: WifiAdapter

    private var deviceAddress: String = ""
    private val wifiList = mutableListOf<WifiInfo>()

    private var esp32IpAddress: String = ""
    private lateinit var sessionManager: SessionManager

    private var passwordDialog: AlertDialog? = null
    private var dialogBinding: DialogWifiPasswordBinding? = null
    private var isConnecting = false

    /** Fungsi siklus hidup Android: dieksekusi saat komponen (Activity/Service) pertama kali dibuat. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        binding = ActivityDeviceConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val deviceName = intent.getStringExtra("device_name") ?: "Unknown"
        deviceAddress  = intent.getStringExtra("device_address") ?: ""

        esp32IpAddress = intent.getStringExtra("esp32_ip") ?: ""

        supportActionBar?.title = deviceName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.toolbar.setPadding(0, systemBars.top, 0, 0)

            binding.contentLayout.setPadding(
                16.dpToPx(),
                16.dpToPx(),
                16.dpToPx(),
                systemBars.bottom + 16.dpToPx()
            )
            insets
        }

        sessionManager = SessionManager(this)

        bleManager = BleManager(this)
        setupRecyclerView()
        setupClickListeners()
        observeState()
        connectToDevice()

        updateStreamButtonVisibility()
    }

    /** Menginisialisasi komponen RecyclerView untuk daftar perangkat/WiFi. */
    private fun setupRecyclerView() {
        wifiAdapter = WifiAdapter { wifiInfo ->
            showPasswordDialog(wifiInfo)
        }

        binding.recyclerWifi.apply {
            layoutManager = LinearLayoutManager(this@DeviceConfigActivity)
            adapter = wifiAdapter
        }
    }

    /** Menyiapkan aksi saat elemen antarmuka ditekan oleh pengguna. */
    private fun setupClickListeners() {
        binding.btnScanWifi.setOnClickListener {
            scanWifi()
        }

        binding.btnStartStream.setOnClickListener {
            if (esp32IpAddress.isNotEmpty()) {
                startActivity(StreamActivity.createIntent(this, esp32IpAddress))
            } else {
                Toast.makeText(this, "IP ESP32 belum diketahui. Lakukan koneksi WiFi terlebih dahulu.", Toast.LENGTH_LONG).show()
            }
        }
    }

/** Memantau perubahan state dari ViewModel atau Flow untuk memperbarui UI. */
private fun observeState() {
    lifecycleScope.launch {
        bleManager.connectionState.collectLatest { state ->
            updateConnectionUI(state)
        }
    }

    lifecycleScope.launch(Dispatchers.Main.immediate) {
        bleManager.receivedData.collect { data ->
            processReceivedData(data)
        }
    }
}

    /** Memperbarui elemen antarmuka sesuai status koneksi saat ini. */
    private fun updateConnectionUI(state: BleManager.ConnectionState) {
        when (state) {
            BleManager.ConnectionState.DISCONNECTED -> {
                binding.tvStatus.text = "Disconnected"
                binding.progressConnection.visibility = View.GONE
                binding.btnScanWifi.isEnabled = false
            }
            BleManager.ConnectionState.CONNECTING -> {
                binding.tvStatus.text = "Connecting..."
                binding.progressConnection.visibility = View.VISIBLE
                binding.btnScanWifi.isEnabled = false
            }
            BleManager.ConnectionState.CONNECTED,
            BleManager.ConnectionState.DISCOVERING_SERVICES -> {
                binding.tvStatus.text = "Discovering services..."
                binding.progressConnection.visibility = View.VISIBLE
                binding.btnScanWifi.isEnabled = false
            }
            BleManager.ConnectionState.READY -> {
                binding.tvStatus.text = "Connected ✓"
                binding.progressConnection.visibility = View.GONE
                binding.btnScanWifi.isEnabled = true
            }
        }
    }

    /** Memproses data mentah (String) yang diterima dari perangkat keras. */
    private fun processReceivedData(data: String) {
        android.util.Log.d("DeviceConfig", "Processing: $data")

        when {
            data.startsWith("STATUS:") -> {
                val status = data.removePrefix("STATUS:")
                binding.tvScanStatus.text = status
                binding.progressWifi.visibility = if (status == "Done") View.GONE else View.VISIBLE

                if (status == "Scanning...") {
                    wifiList.clear()
                    wifiAdapter.submitList(emptyList())
                    android.util.Log.d("DeviceConfig", "WiFi list cleared for new scan")
                }

                if (status == "Done") {
                    binding.tvScanStatus.text = "Done - ${wifiList.size} networks"
                }
            }
            data.startsWith("COUNT:") -> {
                val count = data.removePrefix("COUNT:").toIntOrNull() ?: 0
                binding.tvScanStatus.text = "Found $count networks, receiving..."
                android.util.Log.d("DeviceConfig", "Expected count: $count")
            }
            data.startsWith("BATCH:") -> {
                val batchContent = data.removePrefix("BATCH:")
                val wifiEntries  = batchContent.split(";")
                android.util.Log.d("DeviceConfig", "Received BATCH with ${wifiEntries.size} entries")

                for (entry in wifiEntries) {
                    if (entry.isBlank()) continue
                    val parts = entry.split("|")
                    if (parts.size == 4) {
                        try {
                            val encFull = when (parts[3].trim()) {
                                "O" -> "Open"
                                "S" -> "Secured"
                                else -> parts[3]
                            }
                            val wifi = WifiInfo(
                                index      = parts[0].toInt(),
                                ssid       = parts[1],
                                rssi       = parts[2].toInt(),
                                encryption = encFull
                            )
                            wifiList.add(wifi)
                        } catch (e: Exception) {
                            android.util.Log.e("DeviceConfig", "Parse error: $entry - ${e.message}")
                        }
                    }
                }
                wifiAdapter.submitList(wifiList.toList())
                android.util.Log.d("DeviceConfig", "WiFi list updated, total: ${wifiList.size}")
            }
            data.startsWith("CONNECT:") -> {
                handleConnectResponse(data.removePrefix("CONNECT:"))
            }

            data.startsWith("IP:") -> {
                val ip = data.removePrefix("IP:").trim()
                if (ip.isNotEmpty()) {
                    esp32IpAddress = ip
                    android.util.Log.d("DeviceConfig", "ESP32 IP received: $esp32IpAddress")

                    sessionManager.saveEsp32Ip(ip)

                    if (deviceAddress.isNotEmpty()) {
                        sessionManager.saveLastDeviceMac(deviceAddress)
                    }
                    updateStreamButtonVisibility()
                }
            }
            data.startsWith("BLE:") -> {
                val bleStatus = data.removePrefix("BLE:")
                if (bleStatus == "DISCONNECT") {
                    android.util.Log.d("DeviceConfig", "ESP32 notifying BLE will disconnect")
                    runOnUiThread {
                        passwordDialog?.dismiss()

                        binding.tvStatus.text = "ESP32 ready ✓"
                        binding.tvScanStatus.text = "WiFi connected! Sensor server starting..."
                        binding.progressWifi.visibility = View.GONE
                        binding.btnScanWifi.isEnabled = false

                        Toast.makeText(
                            this,
                            "✓ WiFi terhubung! Tekan 'View Sensor' untuk streaming.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /** Mengatur visibilitas tombol stream berdasarkan ketersediaan IP. */
    private fun updateStreamButtonVisibility() {
        binding.btnStartStream.visibility =
            if (esp32IpAddress.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** Menampilkan popup dialog untuk memasukkan password WiFi. */
    private fun showPasswordDialog(wifiInfo: WifiInfo) {
        dialogBinding = DialogWifiPasswordBinding.inflate(layoutInflater)
        val dialogView = dialogBinding ?: return

        dialogView.tvWifiName.text = wifiInfo.ssid
        dialogView.tvWifiInfo.text = "Signal: ${wifiInfo.rssi} dBm • ${wifiInfo.encryption}"

        dialogView.layoutStatus.visibility = View.GONE
        dialogView.etPassword.text?.clear()
        dialogView.etPassword.isEnabled = true
        isConnecting = false

        passwordDialog = AlertDialog.Builder(this)
            .setTitle("Connect to WiFi")
            .setView(dialogView.root)
            .setPositiveButton("Connect", null)
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .create()

        passwordDialog?.setOnShowListener { dialog ->
            val positiveButton = (dialog as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val password = dialogView.etPassword.text.toString()

                if (password.length < 8 && wifiInfo.encryption != "Open") {
                    dialogView.tilPassword.error = "Password minimal 8 karakter"
                    return@setOnClickListener
                }

                connectToWifi(wifiInfo.ssid, password)
            }
        }

        passwordDialog?.show()
    }

    /** Mengirim instruksi ke perangkat keras untuk terhubung ke router WiFi. */
    private fun connectToWifi(ssid: String, password: String) {
        if (isConnecting) return
        isConnecting = true

        dialogBinding?.let { b ->
            b.etPassword.isEnabled = false
            b.layoutStatus.visibility = View.VISIBLE
            b.tvStatus.text = "Connecting to $ssid..."
            b.progressConnect.visibility = View.VISIBLE
        }

        val success = bleManager.connectWifi(ssid, password)

        if (!success) {
            dialogBinding?.let { b ->
                b.tvStatus.text = "Failed to send command"
                b.progressConnect.visibility = View.GONE
                b.etPassword.isEnabled = true
            }
            isConnecting = false
        }
    }

    /** Memproses balasan (response) setelah percobaan koneksi WiFi. */
    private fun handleConnectResponse(response: String) {
        android.util.Log.d("DeviceConfig", "Connect response: $response")

        runOnUiThread {
            dialogBinding?.let { b ->
                b.progressConnect.visibility = View.GONE

                when {
                    response == "SUCCESS" -> {
                        b.tvStatus.text = "✓ Connected successfully!"
                        b.tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))

                        b.root.postDelayed({
                            passwordDialog?.dismiss()
                            Toast.makeText(this, "WiFi connected!", Toast.LENGTH_SHORT).show()

                            if (esp32IpAddress.isNotEmpty()) {
                                updateStreamButtonVisibility()
                                Toast.makeText(
                                    this,
                                    "Tekan 'View Sensor' untuk melihat live stream",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }, 1500)
                    }
                    response.startsWith("FAILED:") -> {
                        val reason = response.removePrefix("FAILED:")
                        b.tvStatus.text = "✗ Failed: $reason"
                        b.tvStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                        b.etPassword.isEnabled = true
                        isConnecting = false
                    }
                    else -> {
                        b.tvStatus.text = response
                    }
                }
            }
        }
    }

    /** Memulai prosedur koneksi ke perangkat ESP32 utama. */
    private fun connectToDevice() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter          = bluetoothManager.adapter
        val device: BluetoothDevice = adapter.getRemoteDevice(deviceAddress)
        bleManager.connect(device)
    }

    /** Memerintahkan perangkat keras untuk memindai jaringan WiFi yang tersedia. */
    private fun scanWifi() {
        wifiList.clear()
        wifiAdapter.submitList(emptyList())
        binding.progressWifi.visibility = View.VISIBLE
        binding.tvScanStatus.text = "Scanning..."

        val success = bleManager.scanWifi()
        if (!success) {
            Toast.makeText(this, "Failed to send scan command", Toast.LENGTH_SHORT).show()
            binding.progressWifi.visibility = View.GONE
        }
    }

    /** Menangani logika tombol kembali pada action bar. */
    override fun onSupportNavigateUp(): Boolean {
        @Suppress("DEPRECATION")
        onBackPressed()
        return true
    }

    /** Dipanggil saat komponen dihancurkan; membersihkan resource. */
    override fun onDestroy() {
        super.onDestroy()
        passwordDialog?.dismiss()
        bleManager.close()
    }

    inner class WifiAdapter(
        private val onClick: (WifiInfo) -> Unit
    ) : RecyclerView.Adapter<WifiAdapter.ViewHolder>() {

        private var items: List<WifiInfo> = emptyList()

        /** Mengirimkan daftar data terbaru ke Adapter untuk dirender ulang. */
        fun submitList(newItems: List<WifiInfo>) {
            items = newItems
            notifyDataSetChanged()
        }

        /** Membuat dan menginisialisasi ViewHolder untuk item daftar. */
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemWifiBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        /** Mengisi data spesifik ke dalam elemen tampilan pada posisi tertentu. */
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        /** Mengembalikan jumlah total item dalam daftar RecyclerView. */
        override fun getItemCount() = items.size

        inner class ViewHolder(
            private val binding: ItemWifiBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            /** Mengikat data spesifik ke dalam elemen tampilan individual. */
            fun bind(wifi: WifiInfo) {
                binding.tvSsid.text       = wifi.ssid
                binding.tvRssi.text       = "${wifi.rssi} dBm"
                binding.tvEncryption.text = wifi.encryption

                val iconRes = when (wifi.getSignalStrength()) {
                    WifiInfo.SignalStrength.EXCELLENT -> R.drawable.ic_wifi_4
                    WifiInfo.SignalStrength.GOOD      -> R.drawable.ic_wifi_3
                    WifiInfo.SignalStrength.FAIR      -> R.drawable.ic_wifi_2
                    WifiInfo.SignalStrength.WEAK      -> R.drawable.ic_wifi_1
                }
                binding.ivSignal.setImageResource(iconRes)

                binding.root.setOnClickListener { onClick(wifi) }
            }
        }
    }

    /** Mengonversi ukuran dari Density-Independent Pixel (DP) ke Pixel (Px). */
    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
