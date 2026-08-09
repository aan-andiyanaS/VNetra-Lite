package com.airi.vnetra.util

/**
 * VNetraConfig — Single Source of Truth untuk seluruh konstanta sistem VNetra-Lite.
 *
 * Setiap file yang membutuhkan konstanta mengimport dari sini.
 * Tidak ada konstanta fisika/threshold/network key yang boleh hardcoded di file lain.
 */
object VNetraConfig {

    // ── SENSOR ToF ───────────────────────────────────────────────────────────

    /** Jarak minimum valid dari sensor ToF (mm). Di bawah ini dianggap noise. */
    const val TOF_DIST_MIN_MM = 30

    /** Jarak maksimum valid dari sensor ToF (mm). Di atas ini dianggap out-of-range. */
    const val TOF_DIST_MAX_MM = 4000

    /** Alpha EMA untuk smoothing per-sel ToF (SpatialMappingUtils).
     *  tau ≈ 55ms @40Hz — responsif tapi halus. */
    const val TOF_EMA_ALPHA = 0.45f

    /** Frame holdover sebelum sel ToF dianggap kosong/invalid (≈333ms @15FPS). */
    const val TOF_HOLDOVER_FRAMES = 5

    /** Dimensi satu sisi grid ToF sensor (8×8 = 64 sel). */
    const val TOF_GRID_DIM = 8

    // ── SENSOR IMU ───────────────────────────────────────────────────────────

    /** Noise gate IMU: angular rate (°/s) di bawah ini dianggap 0 (diam). */
    const val IMU_NOISE_GATE_DEG_PER_SEC = 4.0f

    // ── PHYSICS (Formula Dynamic Threshold — SSD) ────────────────────────────

    /** d0 — Jarak peringatan minimum absolut (mm). Basis formula Dynamic Threshold.
     *  1000 mm = 1 meter = jarak ergonomi tongkat putih.
     *  Dipakai: NavigationCoordinator (baseWarningDistanceMm) dan TtsAlertManager (seed D_W0). */
    const val BASE_WARNING_DIST_MM = 1000

    /** T_max — Batas atas absolute adaptiveThresholdMm (mm). */
    const val MAX_THRESHOLD_MM = 4000

    /** t_r — Perception-Reaction Time pejalan kaki (detik).
     *  Midpoint [1.1, 1.5] s dari Kovacs and Nagy (2020). */
    const val PERCEPTION_REACTION_TIME_SEC = 1.3f

    /** t_step — Durasi 1 langkah penuh manusia rata-rata (detik).
     *  Dipakai untuk kalkulasi Braking Distance: d_B = 0.5 * a * t_step^2. */
    const val STEP_DURATION_SEC = 0.632f

    /** Alpha EMA untuk velocity approach (NavigationCoordinator). */
    const val VELOCITY_EMA_ALPHA = 0.4f

    /** Spike rejection velocity (mm/s): jump di atas ini = objek transient, diabaikan. */
    const val VELOCITY_SPIKE_THRESHOLD_MMPS = 800f

    /** Max raw approach velocity clamp (mm/s). */
    const val VELOCITY_MAX_MMPS = 2000f

    /** Min delta-distance antar frame untuk menghitung velocity (mm). Di bawah = noise (v=0). */
    const val VELOCITY_MIN_DELTA_MM = 15

    /** Pitch gate: jika pitchAngle (derajat) di atas ini saat obstacle, alert diblokir. */
    const val PITCH_GATE_DEG = 25f

    /** Threshold head-rotation lockout (derajat/s aggregate) untuk isHeadRotating(). */
    const val HEAD_ROTATION_THRESHOLD_DEG = 45f

    /** Multiplier zona DEKAT: d_obj < T * ZONE_NEAR_MULT -> ZONE_DEKAT. */
    const val ZONE_NEAR_MULT = 0.5

    /** Multiplier zona SEDANG: d_obj < T * ZONE_MID_MULT -> ZONE_SEDANG. */
    const val ZONE_MID_MULT = 1.5

    // ── ALERT ────────────────────────────────────────────────────────────────

    /** EPS_NOISE: margin (mm) di atas threshold sebelum alert dimatikan. */
    const val ALERT_EPS_NOISE_MM = 500

    /** EPS_CLEAR_ZONE: margin (mm) untuk konfirmasi "jalan sudah bersih". */
    const val ALERT_EPS_CLEAR_ZONE_MM = 150

    // ── NETWORK / INTENT KEYS ─────────────────────────────────────────────────

    /** Key Intent/SharedPreferences untuk IP address ESP32. Satu definisi untuk semua file. */
    const val KEY_ESP32_IP = "esp32_ip"

    /** Key SharedPreferences untuk MAC address BLE ESP32 terakhir terhubung. */
    const val KEY_LAST_MAC = "last_mac"

    /** Key SharedPreferences untuk IP address yang terakhir digunakan. */
    const val KEY_LAST_IP = "last_ip"
}
