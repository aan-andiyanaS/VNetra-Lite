package com.airi.vnetra.util

/**
 * TofDepthEstimator
 *
 * Bertanggung jawab mengekstrak nilai jarak (depth) per kolom dari sensor VL53L5CX.
 * Mengaplikasikan filter noise sederhana dan kompensasi kemiringan (pitch) dari MPU6050
 * agar pembacaan jarak tetap akurat meski perangkat menunduk/menengadah.
 */

import kotlin.math.roundToInt

object TofDepthEstimator {

    const val D_MAX     = 4000
    const val EPS_NOISE = 30
    const val TOF_FOV_V = 45f

    const val MOUNT_PITCH_DEG = 20f

    /** Menghitung estimasi metrik/jarak final menggunakan kompensasi data sensor gabungan. */
    fun calculate(
        tofData: IntArray,
        j: Int,
        thetaDeg: Float,
        resolution: Int = 8
    ): Int {

        if (j < 0 || j >= resolution) return D_MAX

        val deltaTheta = TOF_FOV_V / resolution
        val centerRow  = (resolution - 1) / 2.0f
        val totalPitch = thetaDeg + MOUNT_PITCH_DEG
        val rCenter = (centerRow + totalPitch / deltaTheta)
            .roundToInt()
            .coerceIn(0, resolution - 1)

        val rows = listOf(
            (rCenter - 1).coerceIn(0, resolution - 1),
            rCenter,
            (rCenter + 1).coerceIn(0, resolution - 1)
        ).distinct()

        var sum   = 0
        var count = 0
        for (r in rows) {
            val idx = r * resolution + j
            if (idx < 0 || idx >= tofData.size) continue
            val z = tofData[idx]

            if (z >= EPS_NOISE && z <= D_MAX) {
                sum   += z
                count++
            }
        }

        return if (count > 0) sum / count else D_MAX
    }
}
