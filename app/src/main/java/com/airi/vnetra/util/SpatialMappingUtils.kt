package com.airi.vnetra.util

import androidx.annotation.VisibleForTesting
import com.airi.vnetra.util.VNetraConfig
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
    
    // Threshold jarak untuk dianggap sebagai ancaman dekat (mm) — dari VNetraConfig
    private val CLOSE_DIST_MIN = VNetraConfig.TOF_DIST_MIN_MM
    private val CLOSE_DIST_MAX = VNetraConfig.TOF_DIST_MAX_MM

    private val emaDistances = FloatArray(64) { -1f }
    private val holdoverFrames = IntArray(64) { 0 }
    // TOF_HOLDOVER_FRAMES: approx 333ms at 15 FPS
    private val MAX_HOLDOVER = VNetraConfig.TOF_HOLDOVER_FRAMES
    // TOF_EMA_ALPHA 0.45: tau ≈ 55ms @40Hz — responsif tapi halus.
    private val EMA_ALPHA = VNetraConfig.TOF_EMA_ALPHA

    @VisibleForTesting
    @Synchronized
    fun reset() {
        emaDistances.fill(-1f)
        holdoverFrames.fill(0)
    }

    @Synchronized fun getSmoothedDistances(): FloatArray = emaDistances.clone()
    @Synchronized fun getHoldoverFrames(): IntArray = holdoverFrames.clone()

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
     *
     * State machine per sel:
     *   Jalur A  rawDist < 0         → Sensor dropout (no target/noise). Pertahankan EMA
     *                                   selama holdoverFrames > 0, lalu hapus.
     *   Jalur B  rawDist ∈ [MIN,MAX] → Objek dalam zona bahaya. Update EMA, reset holdover.
     *   Jalur C  rawDist > MAX        → Sensor berhasil mengukur: objek sudah aman/jauh.
     *                                   Langsung terima data baru, hapus state lama.
     *                                   Jika user maju lagi dan objek kembali ke zona
     *                                   bahaya, EMA diinisialisasi cold-start di Jalur B.
     */
    @Synchronized
    fun analyzeTerrain(tofData: IntArray): ObstacleAnalysis? {
        if (tofData.size != 64) return null

        var nearestDist = Int.MAX_VALUE
        var nearestCol  = 4  // default tengah; diperbarui saat sel terdekat ditemukan
        
        // 1. Update EMA & cari nearestDist + nearestCol dalam 1 pass (O(N))
        for (i in 0..63) {
            val rawDist = tofData[i]

            // Jalur A: Sensor dropout (-1 sentinel dari firmware).
            // Memori EMA dipertahankan selama holdoverFrames > 0 agar alert tidak
            // terputus karena dropout sesaat (misal: pantulan sudut, tepi FoV).
            val dist = if (rawDist < 0) {
                if (holdoverFrames[i] > 0) {
                    holdoverFrames[i]--
                    emaDistances[i].toInt() // Memori masih ada, perlakukan sebagai jarak valid!
                } else {
                    emaDistances[i] = -1f
                    continue // Memori habis, lewati sel ini
                }
            } else if (rawDist in CLOSE_DIST_MIN..CLOSE_DIST_MAX) {
                // Jalur B: Objek ADA di zona bahaya → update EMA, reset holdover.
                holdoverFrames[i] = MAX_HOLDOVER
                if (emaDistances[i] < 0f) {
                    emaDistances[i] = rawDist.toFloat()      // cold-start
                } else {
                    emaDistances[i] = (EMA_ALPHA * rawDist) + ((1f - EMA_ALPHA) * emaDistances[i])
                }
                emaDistances[i].toInt()
            } else {
                // Jalur C: Sensor mengukur jarak valid dan AMAN (objek sudah pergi).
                // BUG-FIX: Jangan gunakan EMA lama selama holdover — itu menyebabkan
                // "Ghost Obstacle" (~333ms false alert setelah objek nyata sudah menghilang).
                // Holdover hanya valid saat sensor DROPOUT (Jalur A), bukan saat sensor
                // berhasil melaporkan jarak aman secara eksplisit.
                holdoverFrames[i] = 0
                emaDistances[i] = -1f
                rawDist
            }

            if (dist in CLOSE_DIST_MIN..CLOSE_DIST_MAX && dist < nearestDist) {
                nearestDist = dist
                nearestCol  = i % 8  // rekam kolom sel terdekat
            }
        }

        if (nearestDist == Int.MAX_VALUE) return null

        // BUG-04 fix: gunakan margin proporsional (30% dari nearestDist) bukan +300 konstan.
        // +300mm terlalu besar saat obstacle jauh (2000+300=2300 — terlalu lebar)
        // dan terlalu kecil saat obstacle dekat (200+300=500 — masih terlalu lebar).
        // nearestDist*1.3 selalu proporsional terhadap jarak actual obstacle.
        val maxDangerDist = (nearestDist * 1.3).toInt()
        // 2. Isolasi area bahaya — hitung rowMask untuk klasifikasi tipe
        var rowMask = 0
        var count   = 0
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
