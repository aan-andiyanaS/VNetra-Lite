package com.airi.vnetra.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"
        val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
        val CHAR_COMMAND_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
        val CHAR_RESPONSE_UUID: UUID = UUID.fromString("cba1d466-344c-4be3-ab3f-189f80dd7518")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val bleScanner by lazy { bluetoothAdapter?.bluetoothLeScanner }

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var responseCharacteristic: BluetoothGattCharacteristic? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val bleJob = SupervisorJob()
    private val bleScope = CoroutineScope(Dispatchers.IO + bleJob)

    private val _receivedData = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val receivedData: SharedFlow<String> = _receivedData

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCOVERING_SERVICES, READY
    }

    private val scanCallback = object : ScanCallback() {
        /** Menerima hasil pindaian (scan) perangkat Bluetooth BLE secara individual. */
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val current = _scanResults.value.toMutableList()
            val idx = current.indexOfFirst { it.device.address == result.device.address }
            if (idx >= 0) current[idx] = result else current.add(result)
            _scanResults.value = current
        }
        /** Menangani kegagalan proses pemindaian BLE dan memberikan log error. */
        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        /** Memantau perubahan status koneksi GATT (terhubung/terputus) dengan ESP32. */
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "GATT connected, requesting MTU 512")
                    _connectionState.value = ConnectionState.CONNECTED

                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "GATT disconnected (status=$status)")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    commandCharacteristic = null
                    responseCharacteristic = null
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                }
            }
        }

        /** Dipanggil saat negosiasi ukuran maksimum paket transmisi (MTU) selesai. */
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu (status=$status)")

            _connectionState.value = ConnectionState.DISCOVERING_SERVICES
            gatt.discoverServices()
        }

        /** Dipanggil saat layanan (services) dan karakteristik BLE pada ESP32 berhasil ditemukan. */
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Target service not found on device")
                return
            }
            commandCharacteristic = service.getCharacteristic(CHAR_COMMAND_UUID)
            responseCharacteristic = service.getCharacteristic(CHAR_RESPONSE_UUID)

            val rspChar = responseCharacteristic
            if (rspChar == null) {
                Log.e(TAG, "Response characteristic not found")
                return
            }

            gatt.setCharacteristicNotification(rspChar, true)
            val descriptor = rspChar.getDescriptor(CCCD_UUID)
            if (descriptor == null) {
                Log.e(TAG, "CCCD not found — fallback to READY without notify guarantee")
                _connectionState.value = ConnectionState.READY
                return
            }
            writeCccd(gatt, descriptor)
        }

        /** Konfirmasi bahwa deskriptor (CCCD) berhasil ditulis untuk mengaktifkan notifikasi. */
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "CCCD written — notify active")

                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                _connectionState.value = ConnectionState.READY
            } else {
                Log.w(TAG, "CCCD write failed (status=$status), retrying once...")
                responseCharacteristic?.getDescriptor(CCCD_UUID)?.let { writeCccd(gatt, it) }
            }
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in Java")
        /** Menerima paket data notifikasi asinkron (seperti status Wi-Fi/IP) dari ESP32. */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (characteristic.uuid == CHAR_RESPONSE_UUID) {
                    dispatchData(characteristic.getStringValue(0) ?: return)
                }
            }
        }

        /** Menerima paket data notifikasi asinkron (seperti status Wi-Fi/IP) dari ESP32. */
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {

            if (characteristic.uuid == CHAR_RESPONSE_UUID) {
                dispatchData(value.toString(Charsets.UTF_8))
            }
        }
    }

    /** Mengurai paket data masuk (seperti JSON Wi-Fi) dan memperbarui Flow status UI. */
    private fun dispatchData(data: String) {
        Log.d(TAG, "RX [${data.length}b]: ${data.take(120)}")
        bleScope.launch { _receivedData.emit(data) }
    }

    /** Menulis deskriptor (CCCD) pada BLE untuk berlangganan aliran data notifikasi. */
    private fun writeCccd(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    /** Mengecek apakah adapter Bluetooth sistem sedang aktif pada perangkat. */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /** Memulai pemindaian perangkat BLE untuk mencari modul ESP32 di sekitar. */
    fun startScan() {
        if (_isScanning.value) return
        _scanResults.value = emptyList()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bleScanner?.startScan(listOf(filter), settings, scanCallback)
        _isScanning.value = true
        Log.d(TAG, "BLE scan started")
    }

    /** Menghentikan proses pemindaian perangkat BLE untuk menghemat baterai. */
    fun stopScan() {
        if (!_isScanning.value) return
        bleScanner?.stopScan(scanCallback)
        _isScanning.value = false
        Log.d(TAG, "BLE scan stopped")
    }

    /** Memulai koneksi ke jaringan atau layanan eksternal. */
    fun connect(device: BluetoothDevice) {
        stopScan()
        _connectionState.value = ConnectionState.CONNECTING

        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Memutus koneksi dari jaringan atau layanan eksternal. */
    fun disconnect() {
        bluetoothGatt?.disconnect()
    }

/** Mengirimkan perintah teks (contoh: setup Wi-Fi) ke modul ESP32 melalui koneksi BLE. */
fun sendCommand(command: String): Boolean {
    Log.d(TAG, "sendCommand called: '$command'")

    val characteristic = commandCharacteristic ?: run {
        Log.e(TAG, "sendCommand failed: commandCharacteristic is null")
        return false
    }

    val gatt = bluetoothGatt ?: run {
        Log.e(TAG, "sendCommand failed: bluetoothGatt is null")
        return false
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val result = gatt.writeCharacteristic(
            characteristic,
            command.toByteArray(Charsets.UTF_8),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.e(TAG, "writeCharacteristic failed, code=$result")
            false
        } else {
            true
        }
    } else {
        @Suppress("DEPRECATION")
        characteristic.value = command.toByteArray(Charsets.UTF_8)
        @Suppress("DEPRECATION")
        gatt.writeCharacteristic(characteristic)
    }
}

    /** Memerintahkan perangkat keras untuk memindai jaringan WiFi yang tersedia. */
    fun scanWifi(): Boolean = sendCommand("SCAN")

    /** Menyatukan SSID dan Password ke format JSON dan mengirimkannya ke ESP32 via BLE. */
    fun connectWifi(ssid: String, password: String): Boolean {
        Log.d(TAG, "Sending CONNECT for SSID: $ssid")
        return sendCommand("CONNECT:$ssid|$password")
    }

    /** Memutus koneksi GATT dengan aman dan membebaskan memori/resource Bluetooth. */
    fun close() {
        stopScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
        commandCharacteristic = null
        responseCharacteristic = null
        bleJob.cancel()
        Log.d(TAG, "BleManager closed")
    }
}
