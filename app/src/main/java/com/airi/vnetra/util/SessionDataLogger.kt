package com.airi.vnetra.util

import android.content.Context
import android.util.Log

/**
 * Data class untuk satu frame data sesi pengujian.
 * Satu frame = satu siklus evaluateObstacles() di StreamService.
 *
 * Mencakup seluruh variabel formula navigasi (d_obj, v_raw, v_avg, M_buffer, T)
 * dan latensi jaringan dalam satu baris CSV — memastikan traceability penuh untuk Bab 4.
 */
data class SessionFrame(
    val timestampMs: Long,
    val dObjMm: Int,
    val vRawMmps: Float,
    val vAvgMmps: Float,
    val mBufferMm: Float,
    val thresholdT: Int,
    val alertTriggered: Boolean,
    val alertText: String,
    val latencyHwMs: Long,
    val latencySerialMs: Long,
    val latencyAlgoMs: Long,
    val latencyTtsMs: Long,
    val latencyBtMs: Long
) {
    val latencyTotalMs: Long
        get() = latencyHwMs + latencySerialMs + latencyAlgoMs + latencyTtsMs + latencyBtMs

    val elapsedS: Long
        get() = 0L // Diisi oleh SessionDataLogger saat penulisan

    fun toCsvRow(sessionStartMs: Long): String {
        val elapsed = (timestampMs - sessionStartMs) / 1000
        return "$timestampMs,$elapsed,$dObjMm," +
            "${"%.2f".format(vRawMmps)},${"%.2f".format(vAvgMmps)}," +
            "${"%.2f".format(mBufferMm)},$thresholdT," +
            "${if (alertTriggered) 1 else 0},\"$alertText\"," +
            "$latencyHwMs,$latencySerialMs,$latencyAlgoMs," +
            "$latencyTtsMs,$latencyBtMs,$latencyTotalMs"
    }
}

/**
 * Data class untuk membungkus seluruh metrik latensi untuk UI Layer.
 * Dipertahankan agar tidak ada breaking change di StreamActivity.
 */
data class LatencyMetrics(
    val hwPing: Long,
    val serialPing: Long,
    val algoPing: Long,
    val ttsPing: Long,
    val btPing: Long,
    val totalPing: Long
)

/**
 * SessionDataLogger
 *
 * Mencatat setiap frame data sesi pengujian ke file CSV Master.
 * Satu baris CSV = satu frame evaluasi (±30 Hz), memuat:
 *   - Variabel formula: d_obj, v_raw, v_avg, M_buffer, Threshold T
 *   - Status alert: apakah peringatan terpicu dan teks TTS-nya
 *   - Latensi jaringan: Hardware, Serial (WebSocket RTT), Algoritma, TTS, Bluetooth, Total
 *
 * File disimpan ke Documents/VNetra_Logs/VNetra_Session_<timestamp>.csv
 * Penulisan dilakukan langsung per-frame (tanpa ring-buffer agregasi)
 * untuk memastikan traceability penuh pada analisis Bab 4.
 */
class SessionDataLogger(context: Context) {

    private val TAG = "SessionLog"

    private var csvWriter: java.io.FileWriter? = null
    private val sessionStartMs = System.currentTimeMillis()
    private var headerWritten = false
    private var frameCount = 0

    init {
        try {
            val timestamp = java.text.SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                java.util.Locale.getDefault()
            ).format(java.util.Date())
            val filename = "VNetra_Session_$timestamp.csv"
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
            )
            val appDir = java.io.File(dir, "VNetra_Logs")
            if (!appDir.exists()) appDir.mkdirs()
            val csvFile = java.io.File(appDir, filename)
            csvWriter = java.io.FileWriter(csvFile, true)
            Log.i(TAG, "SessionDataLogger initialized: ${csvFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init SessionDataLogger", e)
        }
    }

    /** Dipanggil tiap frame evaluasi obstacle — menulis 1 baris CSV secara langsung. */
    fun record(frame: SessionFrame) {
        if (!headerWritten) writeHeader()
        try {
            csvWriter?.append(frame.toCsvRow(sessionStartMs))?.append("\n")
            frameCount++
            // Flush ke disk setiap 30 frame (~1 detik pada 30 Hz) agar data tidak hilang
            // jika aplikasi crash, tanpa overhead flush per-frame.
            if (frameCount % 30 == 0) csvWriter?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write frame", e)
        }
    }

    private fun writeHeader() {
        val header = "timestamp_ms,elapsed_s,d_obj_mm," +
            "v_raw_mmps,v_avg_mmps," +
            "m_buffer_mm,threshold_T_mm," +
            "alert_triggered,alert_text," +
            "latency_hw_ms,latency_serial_ms,latency_algo_ms," +
            "latency_tts_ms,latency_bt_ms,latency_total_ms"
        try {
            csvWriter?.append(header)?.append("\n")
            headerWritten = true
            Log.i(TAG, "CSV header written")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write header", e)
        }
    }

    /** Force-flush sisa buffer ke disk. Dipanggil saat sesi berakhir. */
    fun finalFlush() {
        try {
            csvWriter?.flush()
            csvWriter?.close()
            Log.i(TAG, "SessionDataLogger closed. Total frames: $frameCount")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close SessionDataLogger", e)
        }
    }
}
