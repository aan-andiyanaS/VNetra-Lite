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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import android.speech.tts.UtteranceProgressListener

class TtsAlertManager(private val context: Context) {

    companion object {
        private const val TAG = "TtsAlertManager"

        const val D_W0      = 1000

        const val EPS_NOISE      = 500
        const val EPS_CLEAR_ZONE = 150
    }

    @Volatile private var alertFlag: Boolean = false
    private var lastSpokenTime: Long = 0L

    @Volatile private var resetDebounceFrames = 0
    private var lastCalculatedT: Int = D_W0

    /** Menghitung ambang batas peringatan adaptif untuk deteksi jarak per-objek. */
    fun getAdaptiveThreshold(): Int = lastCalculatedT

    /** Mengecek apakah masih ada peringatan aktif untuk objek apa pun. */
    fun hasActiveAlerts(): Boolean = alertFlag

    private var tts: TextToSpeech? = null
    private val ttsReady = AtomicBoolean(false)
    private val isInitialized: Boolean get() = ttsReady.get()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    data class TtsMessage(val text: String, val flush: Boolean = true)
    private val ttsFlow = MutableSharedFlow<TtsMessage>(extraBufferCapacity = 5, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    @Volatile
    var isMuted: Boolean = false

    private var silentAudioTrack: AudioTrack? = null

    /** Callback yang akan dipicu setiap kali TTS mulai bersuara (membawa nilai latensi dalam milidetik). */
    var onTtsLatencyMeasured: ((Long) -> Unit)? = null

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
                
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val triggerTime = utteranceId?.substringAfter("vnetra_")?.toLongOrNull()
                        if (triggerTime != null) {
                            val latency = System.currentTimeMillis() - triggerTime
                            // Panggil callback agar StreamService tahu latensi aslinya
                            onTtsLatencyMeasured?.invoke(latency)
                        }
                    }
                    override fun onDone(utteranceId: String?) {}
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
                
                ttsReady.set(true)
                Log.d(TAG, "TTS engine siap")

                startA2dpKeepAlive(audioAttributes)

                scope.launch {
                    ttsFlow.collect { msg ->
                        val queueMode = if (msg.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        tts?.speak(msg.text, queueMode, null, "vnetra_${System.currentTimeMillis()}")
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
        dObj: Int,
        clockDirection: Int,
        objectLabel: String = "rintangan",
        isMovingForward: Boolean = true,
        isStationary: Boolean = false,
        vAvg: Float,
        T: Int,
        isAlertPermitted: Boolean,
        isSameSemanticState: Boolean = false,
        headRotationStopTimeMs: Long = 0L
    ): String? {
        val POST_ROTATION_COOLDOWN_MS = 500L
        if (System.currentTimeMillis() - headRotationStopTimeMs < POST_ROTATION_COOLDOWN_MS) {
            return null
        }

        val alreadyAlerted = alertFlag
        val now = System.currentTimeMillis()

        lastCalculatedT = T

        val finalLabel = if (vAvg > 500f) {
            if (isMovingForward) "mendekati $objectLabel" else "$objectLabel mendekat"
        } else objectLabel
        val dirText  = SpatialMappingUtils.clockDirectionToTts(clockDirection)

        val distText = when {
            dObj < T * 0.5 -> "jarak dekat"
            dObj < T * 1.5 -> "jarak sedang"
            else -> "jarak jauh"
        }

        val textToSpeak = "$finalLabel, $distText, $dirText"

        return when {
            dObj < T && !alreadyAlerted -> {

                if (!isAlertPermitted) return null

                // Jika status semantik sama (kepala tidak bergerak, zona tidak memburuk),
                // abaikan sepenuhnya (zero spam).
                if (isSameSemanticState) {
                    Log.d(TAG, "Muted by Semantic Memory (Zone & Heading unchanged)")
                    return null
                }

                if (isMuted) return null

                alertFlag = true
                resetDebounceFrames = 0
                lastSpokenTime = now
                Log.d(TAG, "One-shot triggered: d=${dObj}mm dir=$clockDirection")
                textToSpeak
            }
            dObj < T && alreadyAlerted -> {
                resetDebounceFrames = 0
                val lastSpoken = lastSpokenTime

                // Jika zona memburuk atau kepala menoleh ke objek baru, langsung respons
                if (!isSameSemanticState) {
                    if (isMuted) return null
                    lastSpokenTime = now
                    Log.d(TAG, "Semantic state changed (closer or new heading)")
                    return textToSpeak
                }

                // 1. User bergerak maju terus menerus di zona yang sama: Peringatkan setiap 2.5 detik (Heartbeat)
                if (isMovingForward) {
                    if (now - lastSpoken > 2500L) {
                        lastSpokenTime = now
                        Log.d(TAG, "Moving Alert Update")
                        return textToSpeak
                    }
                }

                // 2. User diam (stationary), tetapi ada objek dinamis mendekat dengan cepat
                if (isStationary && vAvg > 200f) {
                    if (now - lastSpoken > 3000L) {
                        lastSpokenTime = now
                        Log.d(TAG, "Stationary Approaching Object Alert (vAvg=$vAvg)")
                        return textToSpeak
                    }
                }

                null
            }
            dObj > T + EPS_NOISE && alreadyAlerted -> {

                if (isAlertPermitted) {
                    resetDebounceFrames++
                    if (resetDebounceFrames >= 10) {
                        alertFlag = false
                        resetDebounceFrames = 0
                        Log.d(TAG, "Flag reset (D_RESET): d=${dObj}mm")
                        speakQueue("Jalan di depan kosong")
                    }
                } else {
                    resetDebounceFrames = 0
                }
                null
            }
            else -> {
                resetDebounceFrames = 0
                null
            }
        }
    }

    /** Memerintahkan TTS untuk mengucapkan teks tertentu (membatalkan ucapan yang sedang berjalan). */
    fun speak(text: String) {
        if (isMuted || !isInitialized) return
        scope.launch { ttsFlow.emit(TtsMessage(text, flush = true)) }
    }

    /** Memerintahkan TTS untuk menambahkan teks ke dalam antrean (tidak membatalkan ucapan yang sedang berjalan). */
    fun speakQueue(text: String) {
        if (isMuted || !isInitialized) return
        scope.launch { ttsFlow.emit(TtsMessage(text, flush = false)) }
    }

    /** Memaksa TTS mengucapkan teks seketika, menimpa prioritas dan status mute. */
    fun speakForce(text: String) {
        if (!isInitialized) return
        scope.launch { ttsFlow.emit(TtsMessage(text, flush = true)) }
    }

    /** Mereset seluruh memori internal (flags) dari peringatan jarak dan objek. */
    fun resetAllFlags() {
        alertFlag = false
        resetDebounceFrames = 0
        lastSpokenTime = 0L
        Log.d(TAG, "Semua flag one-shot di-reset")
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
        alertFlag = false
        lastSpokenTime = 0L
        scope.cancel()
        Log.d(TAG, "TTS engine shutdown")
    }

}
