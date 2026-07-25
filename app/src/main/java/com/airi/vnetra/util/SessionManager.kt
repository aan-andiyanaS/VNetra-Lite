package com.airi.vnetra.util

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {

    companion object {
        private const val PREF_NAME     = "esp32_session"
        private const val KEY_ESP32_IP  = "esp32_ip"
        private const val KEY_LAST_MAC  = "last_mac"
        private const val KEY_LAST_IP   = "last_ip"
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
