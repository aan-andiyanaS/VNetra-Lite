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
}
