package com.airi.vnetra.util

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DatasetManager(private val context: Context) {
    private var lastSavedTime = 0L
    private val intervalMs = 3000L

    private var currentCount = -1
    private val MAX_DATASET_COUNT = 1500
    private var limitReached = false

    private val storageDir: File by lazy {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "dataset esp32")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    /** Menyimpan data frame JPEG ke penyimpanan jika mode koleksi aktif. */
    suspend fun saveFrameIfNeeded(jpegBytes: ByteArray) {
        if (limitReached) return

        val now = System.currentTimeMillis()
        if (now - lastSavedTime < intervalMs) return
        lastSavedTime = now

        withContext(Dispatchers.IO) {
            val dir = storageDir

            if (currentCount == -1) {
                currentCount = dir.listFiles { file -> file.isFile && file.extension == "jpg" }?.size ?: 0
            }

            if (currentCount >= MAX_DATASET_COUNT) {
                limitReached = true
                Log.d("DatasetManager", "Batas dataset ($MAX_DATASET_COUNT) tercapai. Pengambilan dihentikan.")
                return@withContext
            }

            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(dir, "IMG_${timestamp}.jpg")
                FileOutputStream(file).use { output ->
                    output.write(jpegBytes)
                }
                currentCount++
                if (currentCount >= MAX_DATASET_COUNT) {
                    limitReached = true
                }
                Log.d("DatasetManager", "Saved: ${file.absolutePath} ($currentCount/$MAX_DATASET_COUNT)")
            } catch (e: Exception) {
                Log.e("DatasetManager", "Gagal menyimpan frame", e)
            }
        }
    }
}
