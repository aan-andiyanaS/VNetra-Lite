# Laporan Audit Kode Menyeluruh — VNetra-Lite

**Tanggal:** 2026-08-09  
**Standar:** `/code-review-and-quality` · `/doubt-driven-development` · `/debugging-and-error-recovery`  
**Cakupan:** 14 file (12 Kotlin Android + 1 firmware .ino + 1 model)

---

## Ringkasan Eksekutif

| Severity | Jumlah | Berdampak pada |
|----------|--------|----------------|
| 🔴 **Critical** | 2 | Crash / data loss |
| 🟠 **High** | 5 | Logic bug, fungsi utama salah |
| 🟡 **Medium** | 6 | Edge case, data integrity |
| 🟢 **Low** | 3 | Code quality, maintainability |

---

## 🔴 CRITICAL — Akan Menyebabkan Crash atau Data Loss

### BUG-01: `SpatialMappingUtils` adalah `object` (singleton) — Race Condition pada Reset

**File:** `SpatialMappingUtils.kt` L:14, L:22–23

**Masalah:**
```kotlin
object SpatialMappingUtils {          // singleton — satu instance seumur hidup app
    private val emaDistances = FloatArray(64) { -1f }   // state persisten!
    private val holdoverFrames = IntArray(64) { 0 }     // state persisten!
```

Ketika reconnect, `streamJob` memanggil `SpatialMappingUtils.reset()` dari coroutine di `serviceScope`, sementara `_tofFlow.collect` juga di `serviceScope` dan bisa memanggil `analyzeTerrain()` bersamaan. Meskipun keduanya `@Synchronized`, ada jendela singkat antara `reset()` selesai dan `analyzeTerrain()` berikutnya dimulai, di mana state bisa inkonsisten jika coroutine kedua sudah terjadwal.

**Risiko:** `emaDistances` terbaca parsial, nearestDist kacau, alertFlag salah trigger.

**Fix:**
```kotlin
// Di startStreaming() L:320, reset harus terjadi sebelum UDP/ToF flow aktif kembali
// Gunakan Mutex atau pastikan reset terjadi sebelum collector aktif
```

---

### BUG-02: `SessionDataLogger` — `csvWriter` Tidak Di-null Setelah `finalFlush()`

**File:** `SessionDataLogger.kt` L:129–137

```kotlin
fun finalFlush() {
    try {
        csvWriter?.flush()
        csvWriter?.close()   // ← close tapi tidak set null
    } catch (e: Exception) { ... }
    // csvWriter masih non-null! Panggilan record() setelahnya = IOException
}
```

Jika `record()` dipanggil setelah `finalFlush()` (mis. race di evaluateObstacles saat service shutdown), `csvWriter` non-null tapi sudah closed → `IOException: Stream closed` yang tidak tertangkap → potensial data frame hilang.

**Fix:**
```kotlin
fun finalFlush() {
    try {
        csvWriter?.flush()
        csvWriter?.close()
    } catch (e: Exception) { Log.e(TAG, "Failed to close", e) }
    finally { csvWriter = null }  // tambahkan ini
}
```

---

## 🟠 HIGH — Logic Bug Berdampak pada Fungsi Utama

### BUG-03: `pitchRate` dan `rollRate` Tertukar di `extractFilteredRates()`

**File:** `NavigationCoordinator.kt` L:48–55  
⚠️ **Ini adalah bug terpenting dari kategori High.**

```kotlin
private fun extractFilteredRates(imuData: FloatArray?): Triple<Float, Float, Float> {
    return Triple(
        (imuData?.getOrElse(3) { 0f } ?: 0f).denoised(), // pitchRate  ← SALAH: ini rollRate
        (imuData?.getOrElse(2) { 0f } ?: 0f).denoised(), // rollRate   ← SALAH: ini pitchRate
        (imuData?.getOrElse(4) { 0f } ?: 0f).denoised()  // yawRate    ← BENAR
    )
}
```

Payload firmware:
```cpp
float payload[9] = {
    theta,       // [0] pitch angle
    phi,         // [1] roll angle
    wx_corr_deg, // [2] = pitch rate (rotasi X = maju/mundur kepala)
    wy_corr_deg, // [3] = roll rate  (rotasi Y = miring kepala)
    wz_corr_deg, // [4] = yaw rate
```

`imuData[2]` = pitch rate, tapi diambil sebagai `rollRate`.  
`imuData[3]` = roll rate, tapi diambil sebagai `pitchRate`.

**Konsekuensi:**
- Guard pitch di `isAlertPermitted` menggunakan `pitchRate` yang sebenarnya adalah **roll rate**
- `isHeadRotating()` mendeteksi miring kepala sebagai "pitch" dan sebaliknya
- Kompensasi `v_head_base` menggunakan roll rate yang dianggap pitch rate

**Fix:**
```kotlin
return Triple(
    (imuData?.getOrElse(2) { 0f } ?: 0f).denoised(), // pitchRate = wx_corr = imuData[2]
    (imuData?.getOrElse(3) { 0f } ?: 0f).denoised(), // rollRate  = wy_corr = imuData[3]
    (imuData?.getOrElse(4) { 0f } ?: 0f).denoised()  // yawRate   = wz_corr = imuData[4]
)
```

---

### BUG-04: `maxDangerDist = nearestDist + 300` — Flat Tolerance Menyebabkan False Positive

**File:** `SpatialMappingUtils.kt` L:116

```kotlin
val maxDangerDist = nearestDist + 300
```

Jika `nearestDist = 50mm` (noise sel tunggal yang valid), `maxDangerDist = 350mm`. Semua 64 sel yang jarak ≤350mm masuk zona bahaya — hampir seluruh grid. Dengan ≥4 baris terisi, diklasifikasikan sebagai "tembok" → alert spam.

**Fix:**
```kotlin
// Toleransi 30% relatif, maks 500mm absolut
val maxDangerDist = (nearestDist * 1.3f).toInt().coerceAtMost(nearestDist + 500)
```

---

### BUG-05: `D_W0 = 1200` Tidak Selaras dengan `baseWarningDistanceMm = 1000`

**File:** `TtsAlertManager.kt` L:42 vs `NavigationCoordinator.kt` L:163

```kotlin
// TtsAlertManager:
const val D_W0 = 1200  // komentar: "selaras dengan baseWarningDistanceMm"

// NavigationCoordinator:
fun calculateDynamicThreshold(
    baseWarningDistanceMm: Int = 1000  // ← berbeda!
```

Selama 2.5 detik warmup Mahony, TtsAlertManager menggunakan threshold 1200mm (terlalu sensitif 20% lebih dari desain).

**Fix:**
```kotlin
const val D_W0 = 1000  // selaras dengan baseWarningDistanceMm di NavigationCoordinator
```

---

### BUG-06: `isRestingMode` Dievaluasi pada Rate ToF (15 Hz) bukan IMU (40 Hz)

**File:** `NavigationCoordinator.kt` L:44–45

`updateMovementState()` dipanggil dari `evaluateObstacles()` yang dijalankan tiap `_tofFlow.collect` = 15 Hz. Namun `isRestingMode` bergantung pada `stationaryFrames > 45` = 45÷15Hz = 3 detik.

**Masalah tersembunyi:** Jika tidak ada obstacle, `_tofFlow` mungkin tetap mengirim data (ToF tetap scan), jadi ini mungkin aman. Tapi jika ToF tidak mengirim frame saat ruang kosong di luar range, `stationaryFrames` tidak diincrement → `isRestingMode` tidak pernah aktif → spam alert saat kacamata di meja.

**Fix:** Pindahkan `updateMovementState()` ke `_imuFlow.collect` (40 Hz, selalu aktif).

---

### BUG-07: SSID/Password Mengandung `|` akan Corrupt Perintah WiFi

**File:** `BleManager.kt` L:279

```kotlin
fun connectWifi(ssid: String, password: String): Boolean =
    sendCommand("CONNECT:$ssid|$password")  // separator |
```

Jika SSID = `"MyNetwork|Extra"`, command = `"CONNECT:MyNetwork|Extra|password"`. Firmware akan memparse `SSID="MyNetwork"`, `Password="Extra|password"` — salah.

**Fix:**
```kotlin
// Gunakan encoding URL atau separator yang tidak mungkin ada di SSID
fun connectWifi(ssid: String, password: String): Boolean {
    val encodedSsid = java.net.URLEncoder.encode(ssid, "UTF-8")
    val encodedPass = java.net.URLEncoder.encode(password, "UTF-8")
    return sendCommand("CONNECT:$encodedSsid|$encodedPass")
}
// + firmware harus decode URL encoding
```

---

## 🟡 MEDIUM — Edge Case dan Data Integrity

### BUG-08: Pitch Gate `isAlertPermitted` Tidak Berlaku untuk `"halangan"`

**File:** `NavigationCoordinator.kt` L:241

```kotlin
val isAlertPermitted = !isHeadRotatingNow && !isRestingMode 
    && !(isStaticObst && pitchAngle > 20f)  // hanya "tembok"
```

Lantai saat menunduk dengan <4 baris terisi = `"halangan"` → pitch gate tidak aktif → alert spam.

**Fix:**
```kotlin
val isAlertPermitted = !isHeadRotatingNow && !isRestingMode 
    && !(pitchAngle > 25f)  // berlaku semua tipe
```

---

### BUG-09: `momentumBufferMm` di CSV Dihitung dengan Formula Berbeda dari `NavigationCoordinator`

**File:** `StreamService.kt` L:831 dan L:837

```kotlin
// StreamService (shortcut):
momentumBufferMm = (imuSnap?.getOrElse(5) { 0f } ?: 0f) * 200f

// NavigationCoordinator (formula resmi):
val linearAccelMmps2 = imuData[5] * 1000f
val tStep = 0.632f
val momentumBufferMm = 0.5f * linearAccelMmps2 * (tStep * tStep)
// = imuData[5] × 199.7 ≈ × 200 (kebetulan sama)
```

Hasilnya sama secara numerik, tapi jika `tStep` berubah, CSV tidak akan mencerminkan nilai yang benar-benar digunakan NavigationCoordinator.

**Fix:** Ekspos `momentumBufferMm` dari `ObstaclePhysics` dan gunakan nilai yang sama.

---

### BUG-10: `autoConnectJob` di `MainActivity` Berjalan Selamanya Tanpa Max Retry

**File:** `MainActivity.kt` L:220–246

```kotlin
autoConnectJob = lifecycleScope.launch(Dispatchers.IO) {
    while (isActive) {  // tidak ada batas iterasi
        // coba connect tiap 3 detik
        delay(3000)
    }
}
```

Tidak ada timeout total atau max retry. Jika ESP32 offline permanen, job ini terus berjalan selama MainActivity aktif tanpa memberi tahu user.

---

### BUG-11: `TOF_Task` Polling `isDataReady()` 100Hz untuk Sensor 15Hz

**File:** `firmware-vnetra.ino` L:987

```cpp
vTaskDelay(pdMS_TO_TICKS(10));  // 100Hz polling untuk sensor 15Hz
```

86% polling menemukan `isDataReady() = false` dan membuang waktu CPU. Lebih efisien poll 60ms atau gunakan interrupt VL53L5CX.

---

### BUG-12: Dead Constants di `ToFGridRenderer`

**File:** `ToFGridRenderer.kt` L:28–30

```kotlin
private val HOLDOVER_FRAMES = 5   // tidak digunakan di mana pun
private val TOF_FOV_V = 45f       // tidak digunakan
private val FOV_V = 41f           // tidak digunakan
```

`HOLDOVER_FRAMES` duplikat dari `SpatialMappingUtils.MAX_HOLDOVER` tapi tidak terhubung. Jika `MAX_HOLDOVER` diubah, visual renderer tidak ikut.

---

### BUG-13: `WifiInfo.kt` Tidak Digunakan

**File:** `WifiInfo.kt`

Model ini tidak diimport oleh file lain. Sisa dari versi sebelumnya atau placeholder belum terpakai.

---

## 🟢 LOW — Code Quality

### BUG-14: Dead Variable `mBuffer` di `StreamService`

**File:** `StreamService.kt` L:831

```kotlin
val mBuffer = imuSnap?.getOrElse(5) { 0f }?.times(200f) ?: 0f  // TIDAK PERNAH DIPAKAI
```

Dideklarasikan tapi tidak digunakan — confusion saat membaca kode.

---

### BUG-15: `DeviceAdapter.submitList()` Menggunakan `notifyDataSetChanged()`

**File:** `MainActivity.kt` L:258

```kotlin
notifyDataSetChanged()  // O(N) full rebind, seharusnya DiffUtil
```

---

### BUG-16: Komentar `D_W0` Tidak Diupdate Setelah Konstanta Berubah

**File:** `TtsAlertManager.kt` L:35–42

```kotlin
/**
 * D_W0: ... Nilainya SAMA dengan baseWarningDistanceMm di NavigationCoordinator (1200 mm = ...)
 */
const val D_W0 = 1200  // komentar salah — baseWarningDistanceMm = 1000, bukan 1200
```

---

## Urutan Perbaikan Direkomendasikan

| Prioritas | Bug ID | File | Usaha |
|-----------|--------|------|---------|
| 1 🔴 | BUG-03 | NavigationCoordinator.kt L:51-52 | 2 baris |
| 2 🟠 | BUG-08 | NavigationCoordinator.kt L:241 | 1 baris |
| 3 🟠 | BUG-05 | TtsAlertManager.kt L:42 | 1 baris |
| 4 🟠 | BUG-04 | SpatialMappingUtils.kt L:116 | 1 baris |
| 5 🔴 | BUG-02 | SessionDataLogger.kt L:132 | 3 baris |
| 6 🟠 | BUG-07 | BleManager.kt L:279 | 5 baris + firmware |
| 7 🟡 | BUG-06 | NavigationCoordinator.kt | Refactor sedang |

---

*Laporan ini dihasilkan dari pembacaan line-by-line seluruh codebase menggunakan standar `/code-review-and-quality`, `/doubt-driven-development`, dan `/debugging-and-error-recovery`. Setiap temuan telah di-challenge dengan pertanyaan adversarial sebelum dilaporkan.*
