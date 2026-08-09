package com.airi.vnetra.model

data class WifiInfo(
    val index: Int,
    val ssid: String,
    val rssi: Int,
    val encryption: String
) {
    companion object {
        /** Menerjemahkan string raw JSON respons Wi-Fi menjadi struktur objek WifiInfo. */
        fun fromString(data: String): WifiInfo? {
            if (!data.startsWith("WIFI:")) return null

            // BUG-07 fix (Opsi A): parse dari dua ujung agar SSID yang mengandung '|'
            // tetap di-parse dengan benar — tanpa perubahan firmware.
            // Format ESP32: WIFI:index|ssid|rssi|encryption
            // Strategy: index=parts[0], encryption=parts.last(), rssi=parts[size-2],
            //           ssid = semua yang di antara → joinToString("|").
            val allParts = data.removePrefix("WIFI:").split("|")
            if (allParts.size < 4) return null

            return try {
                WifiInfo(
                    index      = allParts[0].toInt(),
                    ssid       = allParts.subList(1, allParts.size - 2).joinToString("|"),
                    rssi       = allParts[allParts.size - 2].toInt(),
                    encryption = allParts.last()
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Menerjemahkan nilai metrik RSSI dBm menjadi kategori kekuatan sinyal visual. */
    fun getSignalStrength(): SignalStrength {
        return when {
            rssi >= -50 -> SignalStrength.EXCELLENT
            rssi >= -60 -> SignalStrength.GOOD
            rssi >= -70 -> SignalStrength.FAIR
            else        -> SignalStrength.WEAK
        }
    }

    enum class SignalStrength {
        EXCELLENT, GOOD, FAIR, WEAK
    }
}
