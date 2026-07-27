package com.airi.vnetra.util

/**
 * NavigationCoordinator
 *
 * Koordinator utama yang menghubungkan pipeline data MPU6050 dan VL53L5CX
 * dengan sistem peringatan TTS. Mengisolasi logika navigasi dari UI layer
 * sesuai dengan prinsip Single Responsibility (Clean Code).
 */

import kotlin.math.abs
import android.util.Log

class NavigationCoordinator {
    var movingForwardConsecutiveFrames = 0
        private set

    var isStationary = false
        private set

    /** Memperbarui status pergerakan IMU (accelerometer/gyro). */
    fun updateMovementState(imuData: FloatArray?) {
        val pitchRate = imuData?.getOrElse(3) { 0f }?.let { if (abs(it) < 4.0f) 0f else it } ?: 0f
        val rollRate  = imuData?.getOrElse(2) { 0f }?.let { if (abs(it) < 4.0f) 0f else it } ?: 0f
        val yawRate   = imuData?.getOrElse(4) { 0f }?.let { if (abs(it) < 4.0f) 0f else it } ?: 0f
        val aLinMag   = imuData?.getOrElse(5) { 0f } ?: 0f

        val isHeadRotatingLocal = abs(pitchRate) > 45f || abs(yawRate) > 45f || abs(rollRate) > 45f
        isStationary = (movingForwardConsecutiveFrames == 0) && !isHeadRotatingLocal
        
        val isAccelerating = (aLinMag > 1.0f) && !isHeadRotatingLocal
        if (isAccelerating) {
            movingForwardConsecutiveFrames++
        } else {
            movingForwardConsecutiveFrames = 0
        }
    }

    /** Mendeteksi apakah kepala pengguna sedang memutar melebihi ambang batas. */
    fun isHeadRotating(imuData: FloatArray?, threshold: Float = 45f): Boolean {
        val pitchRate = imuData?.getOrElse(3) { 0f }?.let { if (abs(it) < 4.0f) 0f else it } ?: 0f
        val rollRate  = imuData?.getOrElse(2) { 0f }?.let { if (abs(it) < 4.0f) 0f else it } ?: 0f
        val yawRate   = imuData?.getOrElse(4) { 0f }?.let { if (abs(it) < 4.0f) 0f else it } ?: 0f
        return abs(pitchRate) > threshold || abs(yawRate) > threshold || abs(rollRate) > threshold
    }

    // --- Physics State ---
    private var dObjPrev: Int? = null
    private var tsEspPrev: Float? = null
    private val vRawHistory = FloatArray(3)
    var lastCalculatedT: Int = 1200
        private set

    data class ObstaclePhysics(
        val vAvg: Float,
        val dynamicThresholdT: Int,
        val isAlertPermitted: Boolean
    )

    /**
     * Menghitung ambang batas peringatan (T) secara dinamis (Formula G) berdasarkan kecepatan relatif rintangan
     * terhadap pengguna, kecepatan langkah (momentum), dan kompensasi ayunan kepala.
     */
    fun calculateDynamicThreshold(
        dObj: Int,
        objectLabel: String,
        imuData: FloatArray?,
        d_W0: Int = 1200
    ): ObstaclePhysics {
        var vAvg = 0f
        var T = d_W0

        if (imuData != null && imuData.size >= 9) {
            val tsEsp = imuData[6]
            val vHeadBase = imuData[7]
            val isConverged = imuData[8] > 0.5f

            if (isConverged) {
                val dPrev = dObjPrev
                val tsPrev = tsEspPrev
                if (dPrev != null && tsPrev != null && tsEsp != tsPrev) {
                    var dt = (tsEsp - tsPrev) / 1000f
                    if (dt < 0.001f) dt = 0.001f
                    if (dt > 0.5f) dt = 0.5f

                    val vHead = vHeadBase * dObj
                    var vRaw = ((dPrev - dObj) / dt) - vHead
                    if (vRaw < 0f) vRaw = 0f

                    val aLin = imuData[5]
                    val isStaticObject = objectLabel == "tembok"
                    if (isStaticObject && aLin < 2.94f) {
                        vRaw = 0f
                    }

                    vRawHistory[2] = vRawHistory[1]
                    vRawHistory[1] = vRawHistory[0]
                    vRawHistory[0] = vRaw

                    val validCount = vRawHistory.count { it > 0f }
                    vAvg = if (validCount > 0) {
                        (vRawHistory[0] + (if (vRawHistory[1] > 0f) vRawHistory[1] else vRawHistory[0]) + (if (vRawHistory[2] > 0f) vRawHistory[2] else vRawHistory[0])) / 3f
                    } else vRaw

                    val tR = 2.0f
                    val momentumBuffer = imuData[5] * 200f
                    T = (d_W0 + (vAvg * tR) + momentumBuffer).toInt()
                    if (T > 4000) T = 4000
                }
                dObjPrev = dObj
                tsEspPrev = tsEsp
            }
        }
        lastCalculatedT = T
        
        val pitchAngle = imuData?.getOrElse(0) { 0f } ?: 0f
        val isHeadRotatingNow = isHeadRotating(imuData, 45f)
        val isStaticObst = objectLabel == "tembok"
        val isAlertPermitted = !isHeadRotatingNow && !(isStaticObst && pitchAngle > 20f)

        return ObstaclePhysics(vAvg, T, isAlertPermitted)
    }

    fun resetPhysics() {
        dObjPrev = null
        tsEspPrev = null
        for (i in vRawHistory.indices) vRawHistory[i] = 0f
        lastCalculatedT = 1200
    }
}
