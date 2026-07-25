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

            val parts = data.removePrefix("WIFI:").split("|")
            if (parts.size != 4) return null

            return try {
                WifiInfo(
                    index = parts[0].toInt(),
                    ssid = parts[1],
                    rssi = parts[2].toInt(),
                    encryption = parts[3]
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
