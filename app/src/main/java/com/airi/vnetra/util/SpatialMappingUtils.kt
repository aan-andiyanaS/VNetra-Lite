package com.airi.vnetra.util

/**
 * SpatialMappingUtils
 *
 * ADR: Pemetaan spasial MPU6050 & VL53L5CX.
 * Menghitung zona horizontal dan vertikal berbasis ToF untuk sistem feedback TTS,
 * tanpa referensi ke sistem penglihatan komputer (vision/camera).
 * Beroperasi murni pada data array jarak (mm) dengan grid 8x8.
 */

object SpatialMappingUtils {

    // Konstanta Deteksi Tembok (Clean Code)
    const val WALL_MIN_DIST_MM = 30
    const val WALL_MAX_DIST_MM = 1500
    const val WALL_COVERAGE_RATIO = 0.60f
    const val WALL_FLATNESS_TOLERANCE_MM = 300

    const val WALL_TRACKING_ID = 999

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
     * Memeriksa apakah sensor ToF menangkap permukaan tembok/bidang datar (berdasarkan varians dan jangkauan minimum).
     * Menggunakan kompensasi dinamis ketika kepala menunduk.
     */
    fun isWall(tofData: IntArray, thetaDeg: Float): Boolean {
        if (tofData.isEmpty()) return false

        val size = tofData.size
        
        val tolerance = if (thetaDeg > 15f) {
            // Jika menunduk tajam (melihat ke tanah), toleransi kerataan diperketat
            WALL_FLATNESS_TOLERANCE_MM / 2
        } else {
            WALL_FLATNESS_TOLERANCE_MM
        }

        val nearValues = tofData.filter { it in WALL_MIN_DIST_MM..WALL_MAX_DIST_MM }

        if (nearValues.size < size * WALL_COVERAGE_RATIO) return false

        val min = nearValues.minOrNull() ?: return false
        val max = nearValues.maxOrNull() ?: return false

        return (max - min) <= tolerance
    }
}
