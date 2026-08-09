package com.airi.vnetra.util

import android.content.Context
import android.content.SharedPreferences
import com.airi.vnetra.util.VNetraConfig

class SessionManager(context: Context) {

    companion object {
        private const val PREF_NAME    = "esp32_session" // ponytail: local only, not duplicated
        private val KEY_ESP32_IP = VNetraConfig.KEY_ESP32_IP
        private val KEY_LAST_MAC = VNetraConfig.KEY_LAST_MAC
        private val KEY_LAST_IP  = VNetraConfig.KEY_LAST_IP
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** Menyimpan alamat IP ESP32 ke dalam memori lokal. */
    fun saveEsp32Ip(ip: String) {
        prefs.edit()
            .putString(KEY_ESP32_IP, ip)
            .putString(KEY_LAST_IP, ip)
            .apply()
    }

    /** Mengambil alamat IP ESP32 yang terakhir kali tersimpan. */
    fun getSavedEsp32Ip(): String? {
        val ip = prefs.getString(KEY_ESP32_IP, null)
        return if (ip.isNullOrEmpty()) null else ip
    }

    /** Menyimpan MAC address perangkat keras yang terakhir terhubung. */
    fun saveLastDeviceMac(mac: String) {
        prefs.edit().putString(KEY_LAST_MAC, mac).apply()
    }

    /** Mengambil MAC address perangkat yang terakhir digunakan. */
    fun getLastDeviceMac(): String? {
        val mac = prefs.getString(KEY_LAST_MAC, null)
        return if (mac.isNullOrEmpty()) null else mac
    }

    /** Mengambil IP dari perangkat berdasarkan sesi sebelumnya. */
    fun getLastDeviceIp(): String? {
        val ip = prefs.getString(KEY_LAST_IP, null)
        return if (ip.isNullOrEmpty()) null else ip
    }

    /** Menghapus data sesi koneksi yang sedang berjalan. */
    fun clearActiveSession() {
        prefs.edit().remove(KEY_ESP32_IP).apply()
    }

    /** Menghapus seluruh sesi data koneksi secara permanen. */
    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
