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
import com.airi.vnetra.util.VNetraConfig

class NavigationCoordinator {

    companion object {
        // ponytail: konstanta dipindah ke VNetraConfig — tidak ada duplikat di sini.

        // Semantic Zones
        const val ZONE_DEKAT = 1
        const val ZONE_SEDANG = 2
        const val ZONE_JAUH = 3
    }

    /** Menentukan zona semantik dari jarak berdasarkan ambang batas dinamis (adaptiveThresholdMm). */
    fun getDistanceZone(obstacleDistanceMm: Int, adaptiveThresholdMm: Int): Int {
        return when {
            obstacleDistanceMm < adaptiveThresholdMm * VNetraConfig.ZONE_NEAR_MULT -> ZONE_DEKAT
            obstacleDistanceMm < adaptiveThresholdMm * VNetraConfig.ZONE_MID_MULT  -> ZONE_SEDANG
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
        fun Float.denoised() = if (abs(this) < VNetraConfig.IMU_NOISE_GATE_DEG_PER_SEC) 0f else this
        return Triple(
            (imuData?.getOrElse(2) { 0f } ?: 0f).denoised(), // pitchRate = wx_corr_deg = [2] (angguk maju/mundur)
            (imuData?.getOrElse(3) { 0f } ?: 0f).denoised(), // rollRate  = wy_corr_deg = [3] (miring kiri/kanan)
            (imuData?.getOrElse(4) { 0f } ?: 0f).denoised()  // yawRate   = wz_corr_deg = [4] (menoleh) ✓
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
    private var prevObstacleDistanceMm: Int? = null
    private var prevEspTimestampMs: Float? = null
    // ponytail: EMA-A di SpatialMappingUtils (α=0.45) sudah memfilter noise per-sel ToF.
    // EMA-B sekunder di sini dihapus karena hanya menambah phase lag ~62ms tanpa manfaat.
    private var emaVelocityStateMmps: Float = 0f         // EWMA pada output rawApproachVelocityMmps (lebih stabil dari 3-avg)
    private var lastVRaw: Float = 0f        // rawApproachVelocityMmps per-frame terakhir SEBELUM EWMA
    var lastCalculatedT: Int = VNetraConfig.BASE_WARNING_DIST_MM
        private set

    data class ObstaclePhysics(
        val rawApproachVelocityMmps: Float,        // Kecepatan pendekatan sebelum EWMA (per-frame, lebih noisy)
        val emaApproachVelocityMmps: Float,        // Kecepatan pendekatan setelah EWMA (lebih stabil)
        val adaptiveThresholdMm: Int,
        /** Braking distance yang dihitung pada frame ini: 0.5 * |a_lin| * t_step^2.
         *  Di-expose agar StreamService tidak perlu menghitung ulang untuk logging CSV. */
        val momentumBufferMm: Float,
        val isAlertPermitted: Boolean,
        val isSameSemanticState: Boolean,
        /** Status rotasi kepala saat frame ini — di-expose agar StreamService tidak perlu memanggil
         *  isHeadRotating() ulang untuk logging CSV (eliminasi double-call pure function). */
        val isHeadRotating: Boolean
    )

    // --- Obstacle Memory (Semantic State) ---
    private var lastAlertPitch: Float = Float.MAX_VALUE
    private var lastAlertRoll:  Float = Float.MAX_VALUE
    private var lastAlertZone:  Int   = ZONE_JAUH
    // ponytail: track jarak absolut saat alert terakhir; dipakai untuk deteksi kemajuan user (>80mm = state baru).
    private var lastAlertObstacleDistanceMm: Int = Int.MAX_VALUE

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
    fun recordObstacleAlerted(imuData: FloatArray?, obstacleDistanceMm: Int, adaptiveThresholdMm: Int) {
        lastAlertPitch = imuData?.getOrElse(0) { Float.MAX_VALUE } ?: Float.MAX_VALUE
        lastAlertRoll  = imuData?.getOrElse(1) { Float.MAX_VALUE } ?: Float.MAX_VALUE
        lastAlertZone  = getDistanceZone(obstacleDistanceMm, adaptiveThresholdMm)
        lastAlertObstacleDistanceMm = obstacleDistanceMm
        accumulatedYawSinceAlert = 0f
        openSpaceWalkFrames = 0
        Log.d("NavCoord", "Obstacle alerted memory: pitch=$lastAlertPitch roll=$lastAlertRoll zone=$lastAlertZone dist=${obstacleDistanceMm}mm")
    }

    /**
     * Dipanggil saat jalan di depan kembali kosong.
     * Mereset memori semantik agar rintangan berikutnya dinilai sebagai objek baru.
     */
    fun clearObstacleMemory() {
        lastAlertPitch = Float.MAX_VALUE
        lastAlertRoll  = Float.MAX_VALUE
        lastAlertZone  = ZONE_JAUH
        lastAlertObstacleDistanceMm = Int.MAX_VALUE
        accumulatedYawSinceAlert = 0f
        openSpaceWalkFrames = 0
    }

    /**
     * Menghitung ambang batas peringatan (adaptiveThresholdMm) secara dinamis berdasarkan kecepatan relatif rintangan
     * terhadap pengguna, kecepatan langkah (momentum), dan kompensasi ayunan kepala.
     *
     * Formula (Dynamic Threshold berbasis Stopping Sight Distance / SSD):
     *   T = min(4000, d0 + (v_rel * t_r) + d_B)
     *
     * di mana:
     *   d0  = jarak aman minimum absolut = 1000 mm (batas peringatan 1 meter lazim)
     *   v_rel = kecepatan pendekatan relatif terfilter EWMA (mm/s)
     *   t_r = Perception-Reaction Time pejalan kaki ETA [Kovács & Nagy, 2020] = 1.3 detik
     *   d_B = Braking Distance biomekanis (½ × a_lin × t_step²)
     */
    fun calculateDynamicThreshold(
        obstacleDistanceMm: Int,
        objectLabel: String,
        imuData: FloatArray?,
        baseWarningDistanceMm: Int = VNetraConfig.BASE_WARNING_DIST_MM
    ): ObstaclePhysics {

        var emaApproachVelocityMmps = 0f
        var adaptiveThresholdMm = baseWarningDistanceMm
        // braking distance dari formula SSD — diisi di blok isConverged, 0f sebagai fallback awal
        var calculatedMomentumBufferMm = 0f

        if (imuData != null && imuData.size >= 9) {
            val tsEsp = imuData[6]
            val vHeadBase = imuData[7]
            val isConverged = imuData[8] > 0.5f
            // isConverged = flag warmup Mahony AHRS dari firmware ESP32.
            // Bernilai 0.0 selama 100 frame pertama (~2.5 detik pada 40 Hz kirim),
            // lalu 1.0 saat filter sudah stabil. Selama periode ini emaApproachVelocityMmps dan adaptiveThresholdMm
            // tidak dihitung (fallback ke baseWarningDistanceMm = BASE_WARNING_DIST_MM).

            if (isConverged) {
                val dPrev = prevObstacleDistanceMm
                val tsPrev = prevEspTimestampMs

                // ponytail: gunakan obstacleDistanceMm langsung — EMA-A di SpatialMappingUtils sudah menyaring noise.
                // EMA sekunder dihapus untuk mengurangi phase lag pada deteksi kecepatan pendekatan.
                val dSmooth = obstacleDistanceMm

                if (dPrev != null && tsPrev != null && tsEsp != tsPrev) {
                    var dt = (tsEsp - tsPrev) / 1000f
                    if (dt < 0.001f) dt = 0.001f
                    if (dt > 0.5f) dt = 0.5f

                    val vHead = vHeadBase * dSmooth
                    val dDelta = dPrev - dSmooth

                    // rawApproachVelocityMmps: kecepatan pendekatan per-frame SEBELUM EWMA — lebih noisy, mencerminkan nilai mentah.
                    // Menggunakan absolute vHead memastikan kompensasi selalu mengurangi (subtract) 
                    // kecepatan palsu terlepas dari polaritas +/- orientasi fisik MPU.
                    val rawApproachVelocityMmps = if (kotlin.math.abs(dDelta) < VNetraConfig.VELOCITY_MIN_DELTA_MM) 0f
                               else ((dDelta / dt) - kotlin.math.abs(vHead)).coerceIn(0f, VNetraConfig.VELOCITY_MAX_MMPS)

                    // Spike Rejection via Slew-Rate Limiting: jika raw velocity melonjak >800mm/s 
                    // terhadap EMA saat ini, kita cap perubahannya, alih-alih mengabaikannya sepenuhnya.
                    // Ini mencegah "Velocity Lockout" di mana EMA tidak pernah naik jika lonjakan konstan.
                    val velocityJump = rawApproachVelocityMmps - emaVelocityStateMmps
                    val filteredVelocity = when {
                        velocityJump > VNetraConfig.VELOCITY_SPIKE_THRESHOLD_MMPS -> emaVelocityStateMmps + VNetraConfig.VELOCITY_SPIKE_THRESHOLD_MMPS
                        velocityJump < -VNetraConfig.VELOCITY_SPIKE_THRESHOLD_MMPS -> emaVelocityStateMmps - VNetraConfig.VELOCITY_SPIKE_THRESHOLD_MMPS
                        else -> rawApproachVelocityMmps
                    }

                    // EWMA pada filteredVelocity: VELOCITY_EMA_ALPHA → tiap spike baru hanya berkontribusi alpha%.
                    // Lebih stabil dari 3-sample average sekaligus tetap responsif.
                    emaVelocityStateMmps = (VNetraConfig.VELOCITY_EMA_ALPHA * filteredVelocity) + ((1f - VNetraConfig.VELOCITY_EMA_ALPHA) * emaVelocityStateMmps)
                    emaApproachVelocityMmps = emaVelocityStateMmps
                    lastVRaw = rawApproachVelocityMmps  // simpan raw untuk logging CSV, bukan filteredVelocity

                    // t_r dari Kovacs & Nagy [44]; AASHTO 2.5s untuk kendaraan — tidak berlaku untuk pejalan.
                    val perceptionReactionTimeSec = VNetraConfig.PERCEPTION_REACTION_TIME_SEC

                    // --- Kalkulasi Jarak Berhenti Total (SSD) & Kurva Sigmoid (Tanh) ---
                    // aLin: Akselerasi perlambatan absolut (mm/s^2)
                    val aLin = kotlin.math.abs(imuData[5] * 1000f)
                    val tStep = VNetraConfig.STEP_DURATION_SEC
                    val tR = perceptionReactionTimeSec
                    val vAvg = emaApproachVelocityMmps

                    // 1. SSD_raw = v_avg * (t_R + t_step) - 0.5 * |a_lin| * t_step^2
                    val ssdRaw = vAvg * (tR + tStep) - (0.5f * aLin * (tStep * tStep))
                    // SSD = max(0, SSD_raw) untuk mencegah nilai negatif akibat akselerasi tinggi
                    val ssd = kotlin.math.max(0f, ssdRaw)

                    // Simpan momentumBufferMm = bagian braking distance dari formula SSD
                    // agar bisa di-expose ke ObstaclePhysics tanpa menghitung ulang di StreamService.
                    calculatedMomentumBufferMm = 0.5f * aLin * (tStep * tStep)

                    // 2. Normalisasi Rasio (x)
                    val maxThreshold = VNetraConfig.MAX_THRESHOLD_MM.toFloat()
                    val baseWarn = baseWarningDistanceMm.toFloat()
                    val range = maxThreshold - baseWarn
                    
                    // x = SSD / (T_max - d_0)
                    val x = if (range > 0f) ssd / range else 0f

                    // 3. Kalkulasi Batas Akhir (T) dengan fungsi Sigmoid
                    // T = d_0 + [ (T_max - d_0) * tanh(x) ]
                    val multiplier = kotlin.math.tanh(x)
                    adaptiveThresholdMm = (baseWarn + (range * multiplier)).toInt()
                    
                    // Clamping untuk keamanan absolut
                    if (adaptiveThresholdMm > VNetraConfig.MAX_THRESHOLD_MM) adaptiveThresholdMm = VNetraConfig.MAX_THRESHOLD_MM
                    if (adaptiveThresholdMm < baseWarningDistanceMm) adaptiveThresholdMm = baseWarningDistanceMm

                    // 1. Integrasi Relative Yaw Compass (Rotational Shift)
                    val yawRate = imuData[4]
                    val filteredYawRate = if (abs(yawRate) < VNetraConfig.IMU_NOISE_GATE_DEG_PER_SEC) 0f else yawRate
                    accumulatedYawSinceAlert += filteredYawRate * dt
                }
                prevObstacleDistanceMm = dSmooth
                prevEspTimestampMs = tsEsp
            }
        }
        lastCalculatedT = adaptiveThresholdMm

        val pitchAngle = imuData?.getOrElse(0) { 0f } ?: 0f
        val rollAngle  = imuData?.getOrElse(1) { 0f } ?: 0f
        val isHeadRotatingNow = isHeadRotating(imuData, VNetraConfig.HEAD_ROTATION_THRESHOLD_DEG)

        if (wasHeadRotating && !isHeadRotatingNow) {
            headRotationStopTimeMs = System.currentTimeMillis()
        }
        wasHeadRotating = isHeadRotatingNow

        @Suppress("UNUSED_VARIABLE")
        val isStaticObst = objectLabel == "tembok" // ponytail: kept for future zone-specific logic
        // BUG-08 fix: pitch gate berlaku untuk semua tipe objek (bukan hanya "tembok").
        // PITCH_GATE_DEG: menunduk natural berjalan (<20°) tidak memblokir;
        // menunduk aktif melihat lantai (>PITCH_GATE_DEG) memblokir semua alert.
        val isAlertPermitted = !isHeadRotatingNow && !isRestingMode && !(pitchAngle > VNetraConfig.PITCH_GATE_DEG)

        // 2. Evaluasi Pedometer Ruang Terbuka (Translational Shift)
        val aLin = imuData?.getOrElse(5) { 0f } ?: 0f
        if (obstacleDistanceMm > adaptiveThresholdMm && aLin > 1.0f && !isHeadRotatingNow) {
            openSpaceWalkFrames++
        } else if (obstacleDistanceMm <= adaptiveThresholdMm) {
            openSpaceWalkFrames = 0
        }

        // IMU-based Semantic Obstacle Memory: evaluasi Fisika Murni (Tanpa Timer)
        val isTranslationallyValid = openSpaceWalkFrames < 40 // Invalid jika jalan bebas ~2 detik
        val isRestingMode = stationaryFrames > 45
        val currentHeadThreshold = if (isRestingMode) 180f else VNetraConfig.HEAD_ROTATION_THRESHOLD_DEG.toFloat()

        val headingUnchanged = lastAlertPitch != Float.MAX_VALUE &&
            abs(pitchAngle - lastAlertPitch) < currentHeadThreshold &&
            abs(rollAngle  - lastAlertRoll)  < currentHeadThreshold &&
            abs(accumulatedYawSinceAlert)    < currentHeadThreshold

        val currentZone = getDistanceZone(obstacleDistanceMm, adaptiveThresholdMm)

        // isSameSemanticState = TRUE jika orientasi 3D kepala sama (termasuk yaw),
        // rintangan tidak mendekat, dan belum berjalan jauh di ruang kosong.
        // ponytail: 80mm ≈ 1 pijakan kaki; jika user maju sejauh ini sejak alert terakhir, anggap situasi baru.
        val distanceDecreasedSignificantly = lastAlertObstacleDistanceMm - obstacleDistanceMm > 80
        val isSameSemanticState = isTranslationallyValid && headingUnchanged
                                  && (currentZone >= lastAlertZone)
                                  && !distanceDecreasedSignificantly

        return ObstaclePhysics(lastVRaw, emaApproachVelocityMmps, adaptiveThresholdMm, calculatedMomentumBufferMm, isAlertPermitted, isSameSemanticState, isHeadRotatingNow)
    }

    fun resetPhysics() {
        prevObstacleDistanceMm = null
        prevEspTimestampMs = null
        emaVelocityStateMmps = 0f
        lastVRaw = 0f
        lastCalculatedT = VNetraConfig.BASE_WARNING_DIST_MM
        clearObstacleMemory()
    }

    /** Dipanggil saat tidak ada obstacle terdeteksi, agar EWMA velocity tidak tercemar nilai fallback 2500. */
    fun resetDObjSmoothed() {
        emaVelocityStateMmps = 0f
        lastVRaw = 0f
    }
}
