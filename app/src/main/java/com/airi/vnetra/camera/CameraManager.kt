package com.airi.vnetra.camera

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class CameraManager {

    companion object {
        private const val TAG            = "CameraManager"
        private const val FRAME_TYPE_JPEG  = 0x01.toByte()
        private const val FRAME_HEADER_SZ  = 9
        private const val CONNECT_TIMEOUT  = 5L
        private const val READ_TIMEOUT     = 10L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // NONAKTIFKAN timeout baca searah
        .pingInterval(5, TimeUnit.SECONDS) // Aktifkan Ping/Pong otomatis dua arah tiap 5 detik
        .build()

    /** Membuka koneksi WebSocket eksperimental ke aliran data spasial ToF/Visual ESP32. */
    fun streamFrames(ipAddress: String): Flow<ByteArray> = callbackFlow {
        val url = "ws://$ipAddress/ws"
        Log.d(TAG, "Connecting WebSocket: $url")

        val request = Request.Builder().url(url).build()

        val wsListener = object : WebSocketListener() {
            /** Dijalankan saat koneksi WebSocket atau stream berhasil terbuka. */
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
            }

            /** Dijalankan setiap kali menerima paket data baru (string/bytes) dari WebSocket. */
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val raw = bytes.toByteArray()

                if (raw.size < FRAME_HEADER_SZ + 1) return

                val frameType = raw[0]

                if (frameType != FRAME_TYPE_JPEG) return

                val jpeg = raw.copyOfRange(FRAME_HEADER_SZ, raw.size)

                val result = trySend(jpeg)
                if (result.isFailure) {
                    Log.w(TAG, "Frame dropped — collector too slow")
                }
            }

            /** Dijalankan setiap kali menerima paket data baru (string/bytes) dari WebSocket. */
            override fun onMessage(webSocket: WebSocket, text: String) {

                Log.d(TAG, "WS text (unexpected): $text")
            }

            /** Menangani permintaan penutupan WebSocket aliran spasial secara bertahap. */
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
                webSocket.close(1000, null)
            }

            /** Dijalankan saat koneksi WebSocket ditutup secara normal. */
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                close()
            }

            /** Dijalankan saat koneksi WebSocket atau request mengalami kegagalan teknis. */
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                close(t)
            }
        }

        val webSocket = client.newWebSocket(request, wsListener)

        awaitClose {
            Log.d(TAG, "Flow cancelled — closing WebSocket")
            webSocket.close(1000, "Flow cancelled")
        }
    }.flowOn(Dispatchers.IO)

    /** Melakukan ping HTTP singkat untuk mengecek apakah IP ESP32 dapat dijangkau. */
    suspend fun isStreamReachable(ipAddress: String): Boolean {
        return try {
            withTimeout(4_000) {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    val socket = java.net.Socket()
                    try {
                        socket.connect(
                            java.net.InetSocketAddress(ipAddress, 80),
                            3_000
                        )
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "TCP connect failed: ${e.message}")
                        false
                    } finally {
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reachability check timeout: ${e.message}")
            false
        }
    }
}
