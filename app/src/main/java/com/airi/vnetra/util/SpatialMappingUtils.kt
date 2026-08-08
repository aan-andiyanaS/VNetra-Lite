package com.airi.vnetra.util

import androidx.annotation.VisibleForTesting
import kotlin.math.roundToInt

/**
 * SpatialMappingUtils
 *
 * ADR: Pemetaan spasial MPU6050 & VL53L5CX.
 * Menghitung zona horizontal dan vertikal berbasis ToF untuk sistem feedback TTS,
 * tanpa referensi ke sistem penglihatan komputer (vision/camera).
 * Beroperasi murni pada data array jarak (mm) dengan grid 8x8.
 */
object SpatialMappingUtils {

    const val WALL_TRACKING_ID = 999
    
    // Threshold jarak untuk dianggap sebagai ancaman dekat (mm)
    private const val CLOSE_DIST_MIN = 30
    private const val CLOSE_DIST_MAX = 4000

    private val emaDistances = FloatArray(64) { -1f }
    private val holdoverFrames = IntArray(64) { 0 }
    private const val MAX_HOLDOVER = 5 // approx 333ms at 15 FPS
    // EMA_ALPHA 0.45: turun dari 0.6 untuk meredam noise per-sel tanpa lag berlebih.
    // Di 40Hz: tau ≈ 1/(alpha*fps) ≈ 55ms — cukup responsif tapi halus.
    private const val EMA_ALPHA = 0.45f

    @VisibleForTesting
    @Synchronized
    fun reset() {
        emaDistances.fill(-1f)
        holdoverFrames.fill(0)
    }

    fun getSmoothedDistances(): FloatArray = emaDistances
    fun getHoldoverFrames(): IntArray = holdoverFrames

    data class ObstacleAnalysis(
        val type: String,       // "tembok" atau "objek"
        val clockDirection: Int, // Arah jam (10, 11, 12, 1, 2)
        val nearestDistance: Int // Jarak terdekat absolut (mm)
    )

    /** Mengonversi indeks kolom ToF (0..7) ke arah jam referensi spasial (10, 11, 12, 1, 2). */
    fun getColumnClockDirection(column: Int): Int = when (column) {
        0 -> 10
        in 1..2 -> 11
        in 3..4 -> 12
        in 5..6 -> 1
        7 -> 2
        else -> 12
    }

    /** Mengonversi kode arah jam menjadi string bahasa Indonesia untuk Text-to-Speech. */
    fun clockDirectionToTts(clockDirection: Int): String = when (clockDirection) {
        10 -> "arah 10"
        11 -> "arah 11"
        12 -> "arah 12"
         1 -> "arah 1"
         2 -> "arah 2"
        else -> "arah 12"
    }

    /**
     * Menganalisis grid ToF (64 elemen) secara terpusat (Centroid Massa).
     * Jika rintangan membentang vertikal >= 4 baris, diklasifikasikan sebagai "tembok".
     * Arah jam ditentukan oleh pusat massa, dan jarak diambil dari titik terdekat.
     * 
     * Refactor: Zero-allocation algorithm menggunakan bitmask dan primitif.
     */
    @Synchronized
    fun analyzeTerrain(tofData: IntArray): ObstacleAnalysis? {
        if (tofData.size != 64) return null

        var nearestDist = Int.MAX_VALUE
        var nearestCol  = 4  // default tengah; diperbarui saat sel terdekat ditemukan
        
        // 1. Update EMA & cari nearestDist + nearestCol dalam 1 pass (O(N))
        for (i in 0..63) {
            val rawDist = tofData[i]
            if (rawDist < 0) {
                if (holdoverFrames[i] > 0) holdoverFrames[i]-- else emaDistances[i] = -1f
                continue
            }
            
            val dist = if (rawDist in CLOSE_DIST_MIN..CLOSE_DIST_MAX) {
                holdoverFrames[i] = MAX_HOLDOVER
                if (emaDistances[i] < 0f) {
                    emaDistances[i] = rawDist.toFloat()
                } else {
                    emaDistances[i] = (EMA_ALPHA * rawDist) + ((1f - EMA_ALPHA) * emaDistances[i])
                }
                emaDistances[i].toInt()
            } else {
                if (holdoverFrames[i] > 0) {
                    holdoverFrames[i]--
                    emaDistances[i].toInt()
                } else {
                    emaDistances[i] = -1f
                    rawDist
                }
            }

            if (dist in CLOSE_DIST_MIN..CLOSE_DIST_MAX && dist < nearestDist) {
                nearestDist = dist
                nearestCol  = i % 8  // rekam kolom sel terdekat
            }
        }

        if (nearestDist == Int.MAX_VALUE) return null

        // 2. Isolasi area bahaya (toleransi 300mm) — hitung rowMask untuk klasifikasi tipe
        var rowMask = 0
        var count   = 0

        val maxDangerDist = nearestDist + 300
        for (i in 0..63) {
            val d = emaDistances[i].toInt()
            if (d in CLOSE_DIST_MIN..maxDangerDist) {
                rowMask = rowMask or (1 shl (i / 8))
                count++
            }
        }
        
        if (count == 0) return null

        // 3. Syarat tembok: area bahaya membentang vertikal minimal 4 baris
        val distinctRowsCount = Integer.bitCount(rowMask)
        val isWall = distinctRowsCount >= 4
        val type = if (isWall) "tembok" else "objek"

        // 4. Arah jam dari kolom SEL TERDEKAT, bukan centroid rata-rata.
        // Centroid bisa menunjuk berlawanan dari bahaya nyata pada obstacle diagonal/multi-titik.
        // Sel terdekat = titik paling kritis untuk navigasi → selalu prioritaskan arahnya.
        val clockDir = getColumnClockDirection(nearestCol)

        return ObstacleAnalysis(type, clockDir, nearestDist)
    }
}
