package com.airi.vnetra.util

import android.content.Context
import android.util.Log

/**
 * Data class untuk membungkus seluruh metrik latensi untuk kebutuhan UI Layer.
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
 * LatencyLogger
 *
 * Mencatat seluruh nilai latensi ke Logcat dengan tag "LAT" dan mengekspornya ke format CSV secara periodik (setiap 1 detik).
 * File CSV akan disimpan ke dalam folder Documents aplikasi dengan format tanggal dan waktu.
 * Data yang dicatat: Sensor (Hardware), Serial (Transmisi), Algoritma Spasial, TTS, Bluetooth, dan Total E2E.
 */
class LatencyLogger(context: Context) {
    private val TAG = "LAT"
    private val LOG_INTERVAL_MS = 1_000L

    private var csvFile: java.io.File? = null
    private var csvWriter: java.io.FileWriter? = null

    init {
        try {
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
            val filename = "VNetra_Latency_$timestamp.csv"
            val dir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS)
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs()
                csvFile = java.io.File(dir, filename)
                csvWriter = java.io.FileWriter(csvFile, true)
                Log.i(TAG, "CSV Logger initialized: ${csvFile?.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init CSV Logger", e)
        }
    }

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

    private var headerPrinted = false

    /** Mencetak ringkasan statistik ke Logcat dalam format CSV dan menyimpannya ke file. */
    fun flush() {
        if (bufTotal.isEmpty()) return
        val elapsedSec = (System.currentTimeMillis() - sessionStart) / 1000
        
        if (!headerPrinted) {
            val h1 = "Time(s),N,MIN_Sensor,MIN_Serial,MIN_Algo,MIN_TTS,MIN_BT,MIN_Total,AVG_Sensor,AVG_Serial,AVG_Algo,AVG_TTS,AVG_BT,AVG_Total,MAX_Sensor,MAX_Serial,MAX_Algo,MAX_TTS,MAX_BT,MAX_Total"
            Log.i(TAG, h1)
            
            try {
                csvWriter?.append(h1)?.append("\n")
            } catch (e: Exception) { Log.e(TAG, "Failed to write header", e) }
            
            headerPrinted = true
        }

        // --- Ambil Data ---
        val senMin = getMin(bufHardware); val senAvg = getAvg(bufHardware); val senMax = getMax(bufHardware)
        val serMin = getMin(bufSerial);   val serAvg = getAvg(bufSerial);   val serMax = getMax(bufSerial)
        val algMin = getMin(bufAlgo);     val algAvg = getAvg(bufAlgo);     val algMax = getMax(bufAlgo)
        val ttsMin = getMin(bufTts);      val ttsAvg = getAvg(bufTts);      val ttsMax = getMax(bufTts)
        val btMin  = getMin(bufBt);       val btAvg  = getAvg(bufBt);       val btMax  = getMax(bufBt)
        val totMin = getMin(bufTotal);    val totAvg = getAvg(bufTotal);    val totMax = getMax(bufTotal)

        // --- Format CSV Kelompok MIN ---
        val groupMin = "$senMin,$serMin,$algMin,$ttsMin,$btMin,$totMin"
        // --- Format CSV Kelompok AVG ---
        val groupAvg = "$senAvg,$serAvg,$algAvg,$ttsAvg,$btAvg,$totAvg"
        // --- Format CSV Kelompok MAX ---
        val groupMax = "$senMax,$serMax,$algMax,$ttsMax,$btMax,$totMax"

        val row = "$elapsedSec,$sampleCount,$groupMin,$groupAvg,$groupMax"
        // Cetak Baris Data CSV
        Log.i(TAG, row)
        
        try {
            csvWriter?.append(row)?.append("\n")
            csvWriter?.flush()
        } catch (e: Exception) { Log.e(TAG, "Failed to write row", e) }
    }

    private fun getMin(buf: ArrayDeque<Long>) = if (buf.isEmpty()) 0 else buf.min()
    private fun getAvg(buf: ArrayDeque<Long>) = if (buf.isEmpty()) 0 else buf.average().toLong()
    private fun getMax(buf: ArrayDeque<Long>) = if (buf.isEmpty()) 0 else buf.max()

    /** Cetak ringkasan final saat sesi berakhir. */
    fun finalFlush() {
        flush()
        try {
            csvWriter?.close()
            Log.i(TAG, "CSV Logger closed.")
        } catch (e: Exception) { Log.e(TAG, "Failed to close CSV Logger", e) }
    }
}
