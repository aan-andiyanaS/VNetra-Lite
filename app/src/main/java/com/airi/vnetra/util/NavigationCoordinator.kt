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

    companion object {
        /** Batas perubahan sudut kepala (Mahony) sebelum dianggap arah yang berbeda. */
        private const val HEAD_ROTATION_THRESHOLD = 25f

        // Semantic Zones
        const val ZONE_DEKAT = 1
        const val ZONE_SEDANG = 2
        const val ZONE_JAUH = 3
    }

    /** Menentukan zona semantik dari jarak berdasarkan ambang batas dinamis (T). */
    fun getDistanceZone(dObj: Int, T: Int): Int {
        return when {
            dObj < T * 0.5 -> ZONE_DEKAT
            dObj < T * 1.5 -> ZONE_SEDANG
            else -> ZONE_JAUH
        }
    }

    var movingForwardConsecutiveFrames = 0
        private set

    var isStationary = false
        private set

    private var stationaryFrames = 0

    /** True jika sensor telah diam >~3 detik (kacamata di meja / tidak dipakai). */
    val isRestingMode: Boolean
        get() = stationaryFrames > 45

    /** Helper: mengekstrak laju rotasi IMU (pitch/roll/yaw) dengan noise gate 4°/s. */
    private fun extractFilteredRates(imuData: FloatArray?): Triple<Float, Float, Float> {
        fun Float.denoised() = if (abs(this) < 4.0f) 0f else this
        return Triple(
            (imuData?.getOrElse(3) { 0f } ?: 0f).denoised(), // pitchRate
            (imuData?.getOrElse(2) { 0f } ?: 0f).denoised(), // rollRate
            (imuData?.getOrElse(4) { 0f } ?: 0f).denoised()  // yawRate
        )
    }

    /** Memperbarui status pergerakan IMU (accelerometer/gyro). */
    fun updateMovementState(imuData: FloatArray?) {
        val (pitchRate, rollRate, yawRate) = extractFilteredRates(imuData)
        val aLinMag   = imuData?.getOrElse(5) { 0f } ?: 0f

        val isHeadRotatingLocal = abs(pitchRate) > 45f || abs(yawRate) > 45f || abs(rollRate) > 45f
        isStationary = (movingForwardConsecutiveFrames == 0) && !isHeadRotatingLocal
        
        val isAccelerating = (aLinMag > 1.0f) && !isHeadRotatingLocal
        if (isAccelerating) {
            movingForwardConsecutiveFrames++
            stationaryFrames = 0
        } else {
            movingForwardConsecutiveFrames = 0
            if (aLinMag <= 1.0f) {
                stationaryFrames++
            } else {
                // Bergerak (aLinMag > 1.0) tapi tidak dianggap forward — reset stationaryFrames
                // agar isRestingMode tidak terkunci permanen setelah pengguna kembali aktif.
                stationaryFrames = 0
            }
        }
    }

    /** Mendeteksi apakah kepala pengguna sedang memutar melebihi ambang batas. */
    fun isHeadRotating(imuData: FloatArray?, threshold: Float = 45f): Boolean {
        val (pitchRate, rollRate, yawRate) = extractFilteredRates(imuData)
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
        val isAlertPermitted: Boolean,
        val isSameSemanticState: Boolean
    )

    // --- Obstacle Memory (Semantic State) ---
    private var lastAlertPitch: Float = Float.MAX_VALUE
    private var lastAlertRoll:  Float = Float.MAX_VALUE
    private var lastAlertZone:  Int   = ZONE_JAUH
    
    // Invalidator Fisika (Pure Physics Invalidator)
    private var accumulatedYawSinceAlert: Float = 0f
    private var openSpaceWalkFrames: Int = 0

    private var wasHeadRotating = false
    var headRotationStopTimeMs = 0L
        private set

    /**
     * Dipanggil saat TTS berhasil mengucapkan peringatan.
     * Menyimpan snapshot status spasial dan semantik terakhir.
     */
    fun recordObstacleAlerted(imuData: FloatArray?, dObj: Int, T: Int) {
        lastAlertPitch = imuData?.getOrElse(0) { Float.MAX_VALUE } ?: Float.MAX_VALUE
        lastAlertRoll  = imuData?.getOrElse(1) { Float.MAX_VALUE } ?: Float.MAX_VALUE
        lastAlertZone  = getDistanceZone(dObj, T)
        accumulatedYawSinceAlert = 0f
        openSpaceWalkFrames = 0
        Log.d("NavCoord", "Obstacle alerted memory: pitch=$lastAlertPitch roll=$lastAlertRoll zone=$lastAlertZone")
    }

    /**
     * Dipanggil saat jalan di depan kembali kosong.
     * Mereset memori semantik agar rintangan berikutnya dinilai sebagai objek baru.
     */
    fun clearObstacleMemory() {
        lastAlertPitch = Float.MAX_VALUE
        lastAlertRoll  = Float.MAX_VALUE
        lastAlertZone  = ZONE_JAUH
        accumulatedYawSinceAlert = 0f
        openSpaceWalkFrames = 0
    }

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

                    vRawHistory[2] = vRawHistory[1]
                    vRawHistory[1] = vRawHistory[0]
                    vRawHistory[0] = vRaw

                    val validCount = vRawHistory.count { it > 0f }
                    // Trade-off C9: Fallback to vRawHistory[0] if older history is 0.
                    // This intentionally gives double-weight to the latest reading 
                    // which slightly inflates vAvg, increasing the threshold T (safer).
                    vAvg = if (validCount > 0) {
                        (vRawHistory[0] + (if (vRawHistory[1] > 0f) vRawHistory[1] else vRawHistory[0]) + (if (vRawHistory[2] > 0f) vRawHistory[2] else vRawHistory[0])) / 3f
                    } else vRaw

                    val tR = 2.0f
                    val momentumBuffer = imuData[5] * 200f
                    T = (d_W0 + (vAvg * tR) + momentumBuffer).toInt()
                    if (T > 4000) T = 4000

                    // 1. Integrasi Relative Yaw Compass (Rotational Shift)
                    val yawRate = imuData[4]
                    val filteredYawRate = if (abs(yawRate) < 4.0f) 0f else yawRate
                    accumulatedYawSinceAlert += filteredYawRate * dt
                }
                dObjPrev = dObj
                tsEspPrev = tsEsp
            }
        }
        lastCalculatedT = T

        val pitchAngle = imuData?.getOrElse(0) { 0f } ?: 0f
        val rollAngle  = imuData?.getOrElse(1) { 0f } ?: 0f
        val isHeadRotatingNow = isHeadRotating(imuData, 45f)
        
        if (wasHeadRotating && !isHeadRotatingNow) {
            headRotationStopTimeMs = System.currentTimeMillis()
        }
        wasHeadRotating = isHeadRotatingNow

        val isStaticObst = objectLabel == "tembok"
        val isAlertPermitted = !isHeadRotatingNow && !isRestingMode && !(isStaticObst && pitchAngle > 20f)

        // 2. Evaluasi Pedometer Ruang Terbuka (Translational Shift)
        val aLin = imuData?.getOrElse(5) { 0f } ?: 0f
        if (dObj > T && aLin > 1.0f && !isHeadRotatingNow) {
            openSpaceWalkFrames++
        } else if (dObj <= T) {
            openSpaceWalkFrames = 0
        }

        // IMU-based Semantic Obstacle Memory: evaluasi Fisika Murni (Tanpa Timer)
        val isTranslationallyValid = openSpaceWalkFrames < 40 // Invalid jika jalan bebas ~2 detik
        val isRestingMode = stationaryFrames > 45
        val currentHeadThreshold = if (isRestingMode) 180f else HEAD_ROTATION_THRESHOLD

        val headingUnchanged = lastAlertPitch != Float.MAX_VALUE &&
            abs(pitchAngle - lastAlertPitch) < currentHeadThreshold &&
            abs(rollAngle  - lastAlertRoll)  < currentHeadThreshold &&
            abs(accumulatedYawSinceAlert)    < currentHeadThreshold

        val currentZone = getDistanceZone(dObj, T)
        
        // isSameSemanticState = TRUE jika orientasi 3D kepala sama (termasuk yaw), 
        // rintangan tidak mendekat, dan belum berjalan jauh di ruang kosong.
        val isSameSemanticState = isTranslationallyValid && headingUnchanged && (currentZone >= lastAlertZone)

        return ObstaclePhysics(vAvg, T, isAlertPermitted, isSameSemanticState)
    }

    fun resetPhysics() {
        dObjPrev = null
        tsEspPrev = null
        for (i in vRawHistory.indices) vRawHistory[i] = 0f
        lastCalculatedT = 1200
        clearObstacleMemory()
    }
}
