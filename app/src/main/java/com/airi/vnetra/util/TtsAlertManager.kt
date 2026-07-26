package com.airi.vnetra.util

/**
 * TtsAlertManager
 *
 * Mengelola antrean peringatan suara (Text-to-Speech) untuk navigasi hambatan.
 * Menggunakan fusi data dari ToF (jarak) dan MPU6050 (akselerasi & rotasi)
 * untuk menentukan urgensi peringatan dan menghindari spam suara saat pengguna diam (noise gate).
 */

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow

class TtsAlertManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsAlertManager"

        const val D_W0      = 1000

        const val EPS_NOISE      = 500
        const val EPS_CLEAR_ZONE = 150
        const val D_RESET        = D_W0 + EPS_NOISE
    }

    private val alertFlags = ConcurrentHashMap<Int, Boolean>()

    private val lastSeenTime = ConcurrentHashMap<Int, Long>()

    private val lastSpokenTime = ConcurrentHashMap<Int, Long>()

    private val dObjPrev = ConcurrentHashMap<Int, Int>()
    private val tsEspPrev = ConcurrentHashMap<Int, Float>()
    private val vRawHistory = ConcurrentHashMap<Int, FloatArray>()
    private val lastCalculatedT = ConcurrentHashMap<Int, Int>()

    /** Menghitung ambang batas peringatan adaptif untuk deteksi jarak per-objek. */
    fun getAdaptiveThreshold(trackingId: Int): Int = lastCalculatedT[trackingId] ?: D_W0

    private var tts: TextToSpeech? = null
    private val ttsReady = AtomicBoolean(false)
    private val isInitialized: Boolean get() = ttsReady.get()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val ttsFlow = MutableSharedFlow<String>(extraBufferCapacity = 5, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile
    var isMuted: Boolean = false

    private var silentAudioTrack: AudioTrack? = null

    /** Menginisialisasi komponen mesin sintesis suara Text-to-Speech (TTS). */
    fun initTts() {
        if (ttsReady.get()) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                tts?.setAudioAttributes(audioAttributes)

                val result = tts?.setLanguage(Locale("id", "ID"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                    Log.w(TAG, "TTS: Bahasa Indonesia tidak tersedia, fallback ke ${Locale.getDefault()}")
                }
                tts?.setSpeechRate(1.6f)
                ttsReady.set(true)
                Log.d(TAG, "TTS engine siap")

                startA2dpKeepAlive(audioAttributes)

                scope.launch {
                    ttsFlow.collect { text ->
                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vnetra_${System.currentTimeMillis()}")
                    }
                }
            } else {
                Log.e(TAG, "TTS init gagal: status=$status")
            }
        }
    }

    /** Menjaga koneksi Bluetooth headset (A2DP) agar tetap aktif menyala. */
    private fun startA2dpKeepAlive(attributes: AudioAttributes) {
        try {
            val sampleRate = 16000
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            silentAudioTrack = AudioTrack(
                attributes,
                format,
                minBufferSize,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            val silentBuffer = ShortArray(minBufferSize / 2)
            silentAudioTrack?.write(silentBuffer, 0, silentBuffer.size)
            silentAudioTrack?.setLoopPoints(0, silentBuffer.size, -1)
            silentAudioTrack?.play()
            Log.d(TAG, "A2DP Keep-Alive berhasil diaktifkan")
        } catch (e: Exception) {
            Log.e(TAG, "Gagal mengaktifkan A2DP Keep-Alive", e)
        }
    }

    /** Memproses data/input terbaru untuk menjalankan pipeline utama. */
    fun process(
        trackingId: Int,
        dObj: Int,
        clockDirection: Int,
        objectLabel: String = "rintangan",
        isMovingForward: Boolean = true,
        isStationary: Boolean = false,
        imuData: FloatArray? = null
    ): String? {
        val alreadyAlerted = alertFlags[trackingId] ?: false
        val now = System.currentTimeMillis()

        var T = D_W0
        var vAvg = 0f 
        if (imuData != null && imuData.size >= 9) {
            val tsEsp = imuData[6]
            val vHeadBase = imuData[7]
            val isConverged = imuData[8] > 0.5f
            if (isConverged) {
                val dPrev = dObjPrev[trackingId]
                val tsPrev = tsEspPrev[trackingId]
                if (dPrev != null && tsPrev != null && tsEsp != tsPrev) {

                    var dt = (tsEsp - tsPrev) / 1000f
                    if (dt < 0.001f) dt = 0.001f
                    if (dt > 0.5f) dt = 0.5f

                    // ponytail: dObj dalam mm (bukan m) — hasil perkalian besar, tapi T di-cap 4000ms
                    val vHead = vHeadBase * dObj

                    var vRaw = ((dPrev - dObj) / dt) - vHead
                    if (vRaw < 0f) vRaw = 0f

                    val aLin = imuData[5]
                    val isPavingObj = objectLabel.startsWith("paving")
                    val isStaticObject = trackingId == SpatialMappingUtils.WALL_TRACKING_ID || isPavingObj
                    if (isStaticObject && aLin < 2.94f) {
                        vRaw = 0f
                    }

                    val history = vRawHistory.getOrPut(trackingId) { FloatArray(3) }
                    history[2] = history[1]
                    history[1] = history[0]
                    history[0] = vRaw
                    val validCount = history.count { it > 0f }
                    vAvg = if (validCount > 0) {
                        (history[0] + (if (history[1] > 0f) history[1] else history[0]) + (if (history[2] > 0f) history[2] else history[0])) / 3f
                    } else vRaw

                    val tR = 2.0f
                    val momentumBuffer = imuData[5] * 200f
                    T = (D_W0 + (vAvg * tR) + momentumBuffer).toInt()
                    if (T > 4000) T = 4000
                    Log.v(TAG, "Formula G [id=$trackingId]: dt=${String.format("%.3f", dt)} vRaw=${String.format("%.1f", vRaw)} vAvg=${String.format("%.1f", vAvg)} T=$T")
                }

                dObjPrev[trackingId] = dObj
                tsEspPrev[trackingId] = tsEsp
            } else {

                Log.v(TAG, "Formula G [id=$trackingId]: Mahony warming up, T=$D_W0")
            }
        }
        lastCalculatedT[trackingId] = T

        val isPaving = objectLabel.startsWith("paving")

        val finalLabel = if (!isPaving && vAvg > 500f) "$objectLabel mendekat" else objectLabel
        val dirText  = SpatialMappingUtils.clockDirectionToTts(clockDirection)

        val distText = when {
            dObj < T * 0.5 -> "jarak dekat"
            dObj < T * 1.5 -> "jarak sedang"
            else -> "jarak jauh"
        }

        val textToSpeak = if (isPaving && dObj < 500) {
            "$finalLabel, $dirText"
        } else {
            "$finalLabel, $distText, $dirText"
        }

        return when {
            dObj < T && !alreadyAlerted -> {

                val pitchRate = imuData?.getOrElse(2) { 0f } ?: 0f
                val rollRate  = imuData?.getOrElse(3) { 0f } ?: 0f
                val yawRateImu = imuData?.getOrElse(4) { 0f } ?: 0f
                val isHeadRotatingNow = kotlin.math.abs(pitchRate) > 5f ||
                    kotlin.math.abs(yawRateImu) > 5f ||
                    kotlin.math.abs(rollRate) > 5f
                if (isHeadRotatingNow) return null

                val pitchAngle = imuData?.getOrElse(0) { 0f } ?: 0f
                val isStaticObst = trackingId == SpatialMappingUtils.WALL_TRACKING_ID
                if (isStaticObst && pitchAngle > 20f) return null

                val lastSpokenMs = lastSpokenTime[trackingId] ?: 0L
                if (now - lastSpokenMs < 3000L) return null

                if (isMuted) return null

                val isStaticObstacle = trackingId == SpatialMappingUtils.WALL_TRACKING_ID
                if (isStaticObstacle && !isMovingForward) return null

                alertFlags[trackingId] = true
                lastSeenTime[trackingId] = now
                lastSpokenTime[trackingId] = now
                Log.d(TAG, "One-shot triggered: id=$trackingId d=${dObj}mm dir=$clockDirection")
                textToSpeak
            }
            dObj < T && alreadyAlerted -> {

                lastSeenTime[trackingId] = now
                val lastSpoken = lastSpokenTime[trackingId] ?: 0L

                val deltaD = kotlin.math.abs((dObjPrev[trackingId] ?: dObj) - dObj)
                var isMoving = deltaD > 30

                if (!isMovingForward) {
                    isMoving = false
                }

                if (isMoving && (now - lastSpoken > 2000L)) {
                    alertFlags[trackingId] = false
                    Log.d(TAG, "Formula H Reset: Objek $trackingId bergerak (delta=$deltaD)")
                    return null
                }
                
                if (isStationary && isMoving && deltaD > 30) {
                    if (now - lastSpoken > 4000L) {
                        lastSpokenTime[trackingId] = now
                        Log.d(TAG, "Stationary Approaching Object Alert: id=$trackingId")
                        return textToSpeak
                    }
                }
                val isWall = trackingId == SpatialMappingUtils.WALL_TRACKING_ID
                if (isWall && isMovingForward) {
                    if (now - lastSpoken > 1000L) {
                        lastSpokenTime[trackingId] = now
                        Log.d(TAG, "Wall Spam (Moving Forward): id=$trackingId")
                        return textToSpeak
                    }
                }

                if (isPaving && !isMovingForward) {
                    if (now - lastSpoken > 6000L) {
                        lastSpokenTime[trackingId] = now
                        Log.d(TAG, "Stationary Paving Reminder: id=$trackingId")
                        return textToSpeak
                    }
                }
                null
            }
            dObj > D_RESET && alreadyAlerted -> {

                val pitchRate = imuData?.getOrElse(2) { 0f } ?: 0f
                val rollRate  = imuData?.getOrElse(3) { 0f } ?: 0f
                val yawRateImu = imuData?.getOrElse(4) { 0f } ?: 0f
                val isHeadRotatingNow = kotlin.math.abs(pitchRate) > 10f ||
                    kotlin.math.abs(yawRateImu) > 10f ||
                    kotlin.math.abs(rollRate) > 10f
                if (!isHeadRotatingNow) {

                    val isStaticObstacle = trackingId == SpatialMappingUtils.WALL_TRACKING_ID
                    val shouldReset = !isStaticObstacle || isMovingForward
                    if (shouldReset) {
                        alertFlags[trackingId] = false
                        Log.d(TAG, "Flag reset (D_RESET): id=$trackingId d=${dObj}mm moving=$isMovingForward")
                    }
                }
                null
            }
            else -> null
        }
    }

    /** Memerintahkan TTS untuk mengucapkan teks tertentu (membatalkan ucapan yang sedang berjalan). */
    fun speak(text: String) {
        if (isMuted || !isInitialized) return
        scope.launch { ttsFlow.emit(text) }
    }

    /** Memasukkan teks ke antrean TTS untuk diucapkan setelah ucapan sebelumnya selesai. */
    fun speakAdd(text: String) {
        if (isMuted || !isInitialized) return
        if (tts?.isSpeaking == true) return
        scope.launch { ttsFlow.emit(text) }
    }

    /** Memaksa TTS mengucapkan teks seketika, menimpa prioritas dan status mute. */
    fun speakForce(text: String) {
        if (!isInitialized) return
        scope.launch { ttsFlow.emit(text) }
    }

    /** Mereset seluruh memori internal (flags) dari peringatan jarak dan objek. */
    fun resetAllFlags() {
        alertFlags.clear()
        lastSeenTime.clear()
        lastSpokenTime.clear()
        Log.d(TAG, "Semua flag one-shot di-reset (${alertFlags.size} entries)")
    }

    /** Memerintahkan TTS untuk segera menghentikan seluruh ucapan yang sedang berlangsung. */
    fun stopSpeaking() {
        tts?.stop()
    }

    /** Mematikan dan membebaskan sumber daya mesin TTS secara permanen. */
    fun shutdown() {
        silentAudioTrack?.stop()
        silentAudioTrack?.release()
        silentAudioTrack = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady.set(false)
        alertFlags.clear()
        lastSeenTime.clear()
        lastSpokenTime.clear()
        Log.d(TAG, "TTS engine shutdown")
    }

    enum class NavState {
        PATH_CLEAR, WALL_WARNING
    }

    inner class SmartNavigationTts {

        private var currentState = NavState.PATH_CLEAR
        private var lastWarningTime = 0L
        private var lastClearTime = System.currentTimeMillis()
        private var hasGivenSecondClearWarning = true
        private var clearCandidateTime = 0L
        private var dangerCandidateTime = 0L

        /** Mengevaluasi perubahan pada MPU6050 untuk memicu atau meredam peringatan. */
        fun processNavigationState(
            isDanger: Boolean,
            isMovingForward: Boolean,
            isTurning: Boolean,
            isStationary: Boolean = false,
            isHeadRotating: Boolean = false
        ) {
            val now = System.currentTimeMillis()

            if (isDanger) {
                clearCandidateTime = 0L

                if (currentState == NavState.PATH_CLEAR) {
                    if (isHeadRotating) return
                    
                    if (dangerCandidateTime == 0L) {
                        dangerCandidateTime = now
                    } else if (now - dangerCandidateTime > 300L) {
                        currentState = NavState.WALL_WARNING
                        lastWarningTime = now
                        if (!isTurning) {
                            if (isMovingForward) {
                                speak("Awas, tembok di depan")
                            } else if (isStationary) {
                                speak("Objek terdeteksi di depan")
                            } else {
                                speak("Awas, tembok di depan")
                            }
                        }
                        dangerCandidateTime = 0L
                    }
                } else {
                    dangerCandidateTime = 0L
                    if (isMovingForward && !isTurning) {
                        if (now - lastWarningTime > 2000L) {
                            speak("Awas, masih ada tembok")
                            lastWarningTime = now
                        }
                    }
                    // Jika isStationary, HANYA SATU KALI, tidak diulang
                }
            } else {
                dangerCandidateTime = 0L
                if (isHeadRotating) return
                if (currentState == NavState.WALL_WARNING) {
                    if (clearCandidateTime == 0L) {
                        clearCandidateTime = now
                    } else if (now - clearCandidateTime > 500L) {
                        currentState = NavState.PATH_CLEAR
                        lastClearTime = now
                        hasGivenSecondClearWarning = false
                        clearCandidateTime = 0L
                        if (isMovingForward) {
                            speak("Jalan di depan kosong")
                        }
                    }
                } else {
                    clearCandidateTime = 0L
                    if (!isMovingForward) {
                        if (!hasGivenSecondClearWarning && now - lastClearTime > 6000L) {
                            // Diam, jangan berisik
                            hasGivenSecondClearWarning = true
                        }
                    } else {
                        lastClearTime = now
                        hasGivenSecondClearWarning = false
                    }
                }
            }
        }
        /** Mengembalikan seluruh state komponen ke kondisi awal (default). */
        fun resetState() {
            currentState = NavState.PATH_CLEAR
            lastWarningTime = 0L
            lastClearTime = System.currentTimeMillis()
            hasGivenSecondClearWarning = true
            clearCandidateTime = 0L
            dangerCandidateTime = 0L
        }
    }

    val smartNavigation = SmartNavigationTts()
}
