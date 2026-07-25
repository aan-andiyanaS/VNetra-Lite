package com.airi.vnetra.util

/**
 * SpatialMappingUtils
 *
 * ADR: Pemetaan spasial MPU6050 & VL53L5CX.
 * Menghitung zona horizontal dan vertikal berbasis ToF untuk sistem feedback TTS,
 * tanpa referensi ke sistem penglihatan komputer (vision/camera).
 * Beroperasi murni pada data array jarak (mm) dengan grid 4x4 atau 8x8.
 */

object SpatialMappingUtils {

    // Konstanta Deteksi Tembok (Clean Code)
    const val WALL_MIN_DIST_MM = 30
    const val WALL_MAX_DIST_MM = 1500
    const val WALL_COVERAGE_RATIO = 0.60f
    const val WALL_FLATNESS_TOLERANCE_MM = 300

    const val W_VIRTUAL   = 640
    const val H_VIRTUAL   = 480

    const val WALL_TRACKING_ID = 999

    const val TOF_GRID_FRAC = 0.69f

    const val D_LEFT  = 99
    const val D_RIGHT = 99

    const val W_TOF   = W_VIRTUAL - D_LEFT - D_RIGHT

    const val FOV_H_DEG  = 66f
    const val PX_PER_DEG = W_VIRTUAL.toFloat() / FOV_H_DEG

    const val N_COL   = 8
    const val N_ROW   = 8

    const val R_COL   = W_TOF / N_COL

    const val W_Z     = W_TOF / 3

    const val N_COL_4  = 4
    const val R_COL_4  = W_TOF / N_COL_4

    const val B0 = D_LEFT
    const val B1 = D_LEFT + W_Z
    const val B2 = D_LEFT + 2 * W_Z
    const val B3 = D_LEFT + 3 * W_Z

    /** Mengonversi posisi horizontal (X) ke arah jam referensi (10, 11, 12, 1, 2). */
    fun mapToClockDirection(xc: Float): Int = when {
        xc < B0 -> 10
        xc < B1 -> 11
        xc < B2 -> 12
        xc < B3 ->  1
        else    ->  2
    }

    /** Mengonversi kode arah jam menjadi string bahasa Indonesia untuk Text-to-Speech. */
    fun clockDirectionToTts(clockDirection: Int): String = when (clockDirection) {
        10 -> "arah 10"
        11 -> "arah 11"
        12 -> "arah 12"
         1 -> "arah 1"
         2 -> "arah 2"
        else -> "arah depan"
    }

    /** Menentukan indeks kolom sensor ToF berdasarkan posisi horizontal. */
    fun mapToTofColumn(xc: Float): Int {
        val raw = ((xc - D_LEFT) / R_COL).toInt()
        return raw.coerceIn(0, N_COL - 1)
    }

    /** Menghitung lebar zona setiap kolom ToF berdasarkan resolusi yang dipilih. */
    fun rCol(resolution: Int): Int = when (resolution) {
        4    -> R_COL_4
        else -> R_COL
    }

    /** Mengembalikan jumlah total kolom aktif dari resolusi sensor saat ini. */
    fun nCol(resolution: Int): Int = when (resolution) {
        4    -> N_COL_4
        else -> N_COL
    }

    /** Menentukan indeks kolom sensor ToF berdasarkan posisi horizontal. */
    fun mapToTofColumn(xc: Float, resolution: Int = 8): Int {
        val raw = ((xc - D_LEFT) / rCol(resolution)).toInt()
        return raw.coerceIn(0, nCol(resolution) - 1)
    }

    /** Menentukan indeks baris sensor ToF berdasarkan posisi vertikal. */
    fun mapToTofRow(yc: Float, resolution: Int = 8): Int {
        val rowHeight = H_VIRTUAL.toFloat() / resolution
        val raw = (yc / rowHeight).toInt()
        return raw.coerceIn(0, resolution - 1)
    }

    /** Menentukan kolom-kolom bagian tengah (titik fokus) sesuai resolusi aktif. */
    fun centerColumns(): List<Int> = listOf(3, 4)

    /** Mengecek apakah sebuah titik koordinat berada di dalam area deteksi ToF. */
    fun isInTofZone(xc: Float): Boolean =
        xc >= D_LEFT && xc < (D_LEFT + W_TOF)

    /** 
     * Menganalisis matriks ToF untuk menentukan keberadaan rintangan datar (tembok). 
     * Dilengkapi kompensasi kemiringan kepala (Pitch-Aware) agar lantai tidak dikira tembok saat menunduk.
     */
    fun isWall(tofData: IntArray, thetaDeg: Float): Boolean {
        val size = 64
        if (tofData.size != size) return false

        // Jika menunduk tajam (melihat ke tanah), toleransi kerataan diperketat
        // agar aspal/lantai tidak lolos seleksi sebagai rintangan.
        val tolerance = if (thetaDeg < -15f) {
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
