# Bug Report: Paradoks Peringatan VNetra-Lite

**Versi Firmware:** v2 (ESP32 + VL53L5CX + MPU6050)  
**Tanggal Audit:** 2026-08-08  
**Auditor:** Antigravity Code Review Engine  
**Severity:** 🔴 Kritis (dua bug berlawanan arah saling memperparah satu sama lain)

---

## Ringkasan Eksekutif

Sistem VNetra-Lite menunjukkan **paradoks perilaku** yang membuatnya sekaligus:
1. **Terlalu sensitif** → Spam peringatan saat pengguna diam.
2. **Terlalu lamban** → Delay atau bahkan bungkam saat pengguna maju ke tembok.

Kedua masalah ini bukan kerusakan acak. Keduanya berakar pada **lima titik kode spesifik** yang dapat dilacak dan diperbaiki secara tepat. Dokumen ini menjelaskan setiap titik tersebut lengkap dengan trace eksekusi nyata dan rekomendasi perbaikan.

---

## Bagian 1: Masalah A — Spam Alert Saat Pengguna Diam

### Gejala
Peringatan TTS berbunyi berulang-ulang walaupun pengguna berdiri diam di tempat dan tidak ada rintangan yang bergerak mendekatinya.

---

### Bug A1 — `momentumBufferMm` Menggunakan Konversi Satuan yang Salah

**File:** `app/src/main/java/com/airi/vnetra/util/NavigationCoordinator.kt`  
**Baris:** 209–214

```kotlin
// Kode Saat Ini (BERMASALAH)
val linearAccelMmps2 = imuData[5] * 1000f   // ← konversi m/s² ke mm/s²
val tStep = 0.632f                           // satuan: detik
val momentumBufferMm = 0.5f * linearAccelMmps2 * (tStep * tStep)
// = 0.5 × (a_lin × 1000) × 0.4 = a_lin × 200
```

**Simulasi nilai saat diam (getaran ringan, a_lin ≈ 0.8 m/s²):**
```
momentumBufferMm = 0.5 × (0.8 × 1000) × (0.632²)
                 = 0.5 × 800 × 0.40
                 = 160 mm  ← threshold naik 160mm tiba-tiba
```

**Simulasi spike napas/gemetar (a_lin ≈ 2.0 m/s²):**
```
momentumBufferMm = 0.5 × (2.0 × 1000) × 0.40 = 400 mm
adaptiveThreshold = 1200 + 0 + 400 = 1600 mm
```
Sebuah rintangan yang sebelumnya aman di jarak 1400mm **tiba-tiba masuk zona bahaya** karena threshold melonjak 400mm hanya akibat getaran kecil saat diam.

**Akar Masalah:** Nilai `imuData[5]` dari firmware sudah dalam satuan `m/s²`. Formula `½ × a × t²` untuk menghitung "jarak lunge" memerlukan `a` dalam `m/s²` dan menghasilkan meter, atau `a` dalam `mm/s²` dan menghasilkan `mm`. Namun, nilai `a` sudah dikali 1000 (dikonversi ke mm/s²), sementara `tStep` masih dalam detik — hasilnya `mm`, bukan `meter`. Ini konsisten, **tetapi angkanya menjadi terlalu besar** karena `momentumBufferMm` seharusnya merepresentasikan "seberapa jauh user bisa melangkah dalam 0.6 detik", bukan "gaya akselerasi yang dikonversi ke unit salah". Nilai semestinya jauh lebih kecil.

**Perbaikan yang Disarankan:**
```kotlin
// OPSI A: Hapus konversi × 1000, biarkan dalam m/s² dan skala manual
val momentumBufferMm = 0.5f * imuData[5] * (tStep * tStep) * 200f
// Faktor 200 = tuning empiris yang masuk akal (≈ 0.5 × 1 m/s² × 0.4s² × 1000 mm/m)

// OPSI B (lebih sederhana): Kap nilai buffer
val momentumBufferMm = (0.5f * linearAccelMmps2 * (tStep * tStep)).coerceAtMost(150f)
```
> **Rekomendasi: Opsi B** — Pasang batas atas `coerceAtMost(150f)` sebagai perbaikan cepat. Ini memastikan momentum buffer tidak pernah melebihi 150mm, sehingga threshold tidak akan melonjak lebih dari 1350mm hanya karena getaran.

---

### Bug A2 — `isSameSemanticState` Tidak Memblok Alert Pertama Saat Diam

**File:** `app/src/main/java/com/airi/vnetra/util/TtsAlertManager.kt`  
**Baris:** 197–206

```kotlin
// Blok PERTAMA (belum pernah alert):
obstacleDistanceMm < adaptiveThresholdMm && !alreadyAlerted -> {
    if (!isAlertPermitted) return null   // hanya cek rotasi & restingMode
    if (isSameSemanticState) return null
    // ← TIDAK ADA pengecekan isStationary di sini!
    alertFlag = true
    textToSpeak  // ← Alert pertama SELALU muncul jika threshold naik akibat Bug A1
}
```

Karena **Bug A1 menaikkan threshold secara acak**, kondisi `obstacleDistanceMm < adaptiveThresholdMm` bisa menjadi `true` meski rintangan tidak bergerak. Alert pertama langsung berbunyi karena tidak ada guard `isStationary`.

**Perbaikan yang Disarankan:**
```kotlin
obstacleDistanceMm < adaptiveThresholdMm && !alreadyAlerted -> {
    if (!isAlertPermitted) return null
    if (isSameSemanticState) return null

    // [FIX A2] Guard tambahan: jika user diam DAN rintangan tidak mendekat, tahan.
    // Tanpa ini, spike threshold dari Bug A1 akan terus memicu alert pertama.
    if (isStationary && emaApproachVelocityMmps < 80f) return null

    if (isMuted) return null
    alertFlag = true
    ...
}
```

---

### Bug A3 — EMA Alpha Terlalu Tinggi di `SpatialMappingUtils`

**File:** `app/src/main/java/com/airi/vnetra/util/SpatialMappingUtils.kt`  
**Baris:** 27

```kotlin
private const val EMA_ALPHA = 0.45f
```

Pada 40Hz, dengan alpha 0.45, time constant tau ≈ 55ms. Sensor VL53L5CX memiliki noise ambient IR (terutama di dalam ruangan dengan lampu LED). Satu sel yang melompat dari 800mm → 200mm dalam 1 frame dapat menarik nilai `nearestDist` turun drastis, yang kemudian membuat "zona bahaya" ikut menyempit. Ini menciptakan efek "rintangan mendekat" palsu.

**Perbaikan yang Disarankan:**
```kotlin
// Turunkan alpha untuk meredam spike per-sel lebih baik
// tau ≈ 1/(0.30 × 40) ≈ 83ms — masih cukup responsif tapi lebih halus
private const val EMA_ALPHA = 0.30f  // turun dari 0.45
```

---

## Bagian 2: Masalah B — Alert Delay/Bungkam Saat Pengguna Maju ke Tembok

### Gejala
Saat pengguna berjalan dengan arah langsung ke tembok, peringatan audio sangat terlambat (baru berbunyi saat jarak sudah sangat dekat) atau sama sekali tidak berbunyi hingga pengguna hampir menabrak.

---

### Bug B1 — Dead Zone 15mm Memblok Deteksi Kecepatan Pendekatan Lambat

**File:** `app/src/main/java/com/airi/vnetra/util/NavigationCoordinator.kt`  
**Baris:** 196–197

```kotlin
val rawApproachVelocityMmps = if (kotlin.math.abs(dDelta) < 15) 0f
           else ((dDelta / dt) - kotlin.math.abs(vHead)).coerceIn(0f, 2000f)
```

**Trace untuk pengguna berjalan pelan (~0.3 m/s = 7.5mm/frame pada 40Hz):**
```
Frame N:   dDelta = 7mm  → |7| < 15 → rawV = 0f   (DIBLOK)
Frame N+1: dDelta = 8mm  → |8| < 15 → rawV = 0f   (DIBLOK)
Frame N+2: dDelta = 13mm → |13| < 15 → rawV = 0f  (DIBLOK)
Frame N+3: dDelta = 9mm  → |9| < 15 → rawV = 0f   (DIBLOK)
```
**`emaApproachVelocityMmps` tetap 0** selamanya. `adaptiveThresholdMm` tidak pernah tumbuh dari baseline 1200mm. Tidak ada "Stopping Sight Distance" yang bekerja — threshold yang seharusnya adaptif justru bersifat statis.

**Trace untuk pengguna berjalan cepat (~1.2 m/s = 30mm/frame):**
```
Frame N:   dDelta = 30mm → rawV = (30/0.025) - vHead = ~1200 mm/s ✓
Frame N+1: ema = 0.4×1200 + 0.6×prev → butuh 3-4 frame untuk stabil
```
Berjalan cepat bekerja, tapi hanya setelah delay buildup EWMA ~3-4 frame (~75-100ms).

**Perbaikan yang Disarankan:**
```kotlin
// Turunkan dead zone dari 15mm ke 8mm
// 8mm ≈ pergerakan minimal yang bisa diandalkan dari sensor ToF pada 40Hz
val rawApproachVelocityMmps = if (kotlin.math.abs(dDelta) < 8) 0f
           else ((dDelta / dt) - kotlin.math.abs(vHead)).coerceIn(0f, 2000f)
```

---

### Bug B2 — `isSameSemanticState` Membungkam Alert Lanjutan Secara Berlebihan

**File:** `app/src/main/java/com/airi/vnetra/util/NavigationCoordinator.kt`  
**Baris:** 258–262

```kotlin
val currentZone = getDistanceZone(obstacleDistanceMm, adaptiveThresholdMm)
val isSameSemanticState = isTranslationallyValid && headingUnchanged 
                          && (currentZone >= lastAlertZone)  // ← MASALAH
```

**Definisi zone (getDistanceZone):**
- `ZONE_DEKAT`: jarak < `threshold × 0.5` (misalnya < 600mm)
- `ZONE_SEDANG`: jarak < `threshold × 1.5` (misalnya < 1800mm)  
- `ZONE_JAUH`: di atas itu

**Trace skenario maju ke tembok (threshold = 1200mm):**
```
t=0: Jarak 1100mm → ZONE_SEDANG → Alert pertama fired! recordObstacleAlerted(zone=SEDANG)
t=1: Jarak 1050mm → ZONE_SEDANG (masih < 1800mm), currentZone >= lastAlertZone → TRUE
     isSameSemanticState = TRUE → ALERT DIBUNGKAM ❌
t=2: Jarak 900mm  → ZONE_SEDANG → DIBUNGKAM LAGI ❌
t=3: Jarak 750mm  → ZONE_SEDANG → DIBUNGKAM LAGI ❌
t=4: Jarak 590mm  → ZONE_DEKAT (< 600mm) → state BARU → alert muncul!
```

Pengguna sudah **59cm dari tembok** sebelum alert kedua berbunyi. Rentang ZONE_SEDANG (600–1800mm = 1.2 meter!) terlalu lebar — pengguna bisa melangkah 1.2 meter tanpa peringatan apapun.

**Perbaikan yang Disarankan:**
```kotlin
// Tambahkan tracking jarak saat alert terakhir
private var lastAlertObstacleDistanceMm: Int = Int.MAX_VALUE

fun recordObstacleAlerted(imuData: FloatArray?, obstacleDistanceMm: Int, adaptiveThresholdMm: Int) {
    ...
    lastAlertObstacleDistanceMm = obstacleDistanceMm  // ← tambahkan ini
}

// Ubah isSameSemanticState:
val distanceDecreased = lastAlertObstacleDistanceMm - obstacleDistanceMm > 80  // ← 80mm = 1 langkah maju
val isSameSemanticState = isTranslationallyValid && headingUnchanged 
                          && (currentZone >= lastAlertZone)
                          && !distanceDecreased  // ← tambahkan guard ini
```
Dengan ini, setiap kali pengguna maju lebih dari 80mm sejak alert terakhir, state dianggap "baru" dan alert dikirim.

---

### Bug B3 — `isMovingForward` Sulit Aktif Karena Filter Berlapis di Firmware

**File:** `firmware-vnetra/firmware-vnetra/firmware-vnetra.ino` (Baris 824–847)  
**File:** `app/src/main/java/com/airi/vnetra/util/NavigationCoordinator.kt` (Baris 65)

Nilai `a_lin_mag` yang dikirim dari ESP32 melewati **4 lapisan filter berturut-turut**:
1. Spike rejection > 20 m/s²  
2. EMA adaptif (alpha=0.15 saat diam, 0.4 saat bergerak)
3. DC bias subtraction
4. Noise gate 0.3 m/s²

Berjalan biasa di atas lantai menghasilkan `a_lin_dynamic ≈ 0.5–0.8 m/s²`. Threshold di Android adalah `aLinMag > 1.0f`:

```kotlin
// NavigationCoordinator.kt L:65
val isAccelerating = (aLinMag > 1.0f) && !isHeadRotatingLocal
// 0.8 > 1.0 = false → movingForwardConsecutiveFrames tidak pernah naik → isMovingForward = false
```

Akibatnya, blok "Heartbeat 2.5 detik" di `TtsAlertManager.kt:229-235` tidak pernah aktif:
```kotlin
if (isMovingForward) {  // ← selalu false untuk jalan biasa!
    if (now - lastSpoken > 2500L) { return textToSpeak }
}
```

**Perbaikan yang Disarankan:**

**Opsi 1 (di Android):** Turunkan threshold `isMovingForward`:
```kotlin
// NavigationCoordinator.kt L:65
val isAccelerating = (aLinMag > 0.6f) && !isHeadRotatingLocal  // turun dari 1.0
```

**Opsi 2 (lebih baik):** Buat heartbeat berbasis penurunan jarak, bukan hanya IMU:
```kotlin
// TtsAlertManager.kt — di dalam blok alreadyAlerted
if (isMovingForward || emaApproachVelocityMmps > 80f) {
    if (now - lastSpoken > 2500L) {
        lastSpokenTime = now
        return textToSpeak
    }
}
```
> **Rekomendasi: Opsi 2** — Lebih robust karena tidak bergantung pada kualitas sinyal IMU yang sangat bervariasi antar individu pengguna.

---

## Tabel Prioritas Perbaikan

| # | Bug | Severity | File | Baris | Dampak Perbaikan |
|---|-----|----------|------|-------|-----------------|
| **1** | **A1** momentumBuffer terlalu besar | 🔴 Kritis | `NavigationCoordinator.kt` | 209–214 | Menghilangkan akar penyebab spam saat diam |
| **2** | **B2** `isSameSemanticState` terlalu agresif | 🔴 Kritis | `NavigationCoordinator.kt` | 258–262 | Menghilangkan akar penyebab delay alert maju |
| **3** | **A2** Tidak ada guard `isStationary` di alert pertama | 🟠 Tinggi | `TtsAlertManager.kt` | 197–206 | Mencegah false alarm pertama saat diam |
| **4** | **B3** Heartbeat tidak aktif saat jalan biasa | 🟠 Tinggi | `TtsAlertManager.kt` | 229–234 | Peringatan berulang saat maju ke rintangan |
| **5** | **B1** Dead zone 15mm terlalu besar | 🟡 Sedang | `NavigationCoordinator.kt` | 196 | Kecepatan pendekatan lambat terdeteksi |
| **6** | **A3** EMA alpha 0.45 terlalu reaktif | 🟡 Sedang | `SpatialMappingUtils.kt` | 27 | Noise per-sel ToF lebih halus |

---

## Urutan Perbaikan yang Direkomendasikan

### Tahap 1 — Perbaikan Paling Impactful (Selesaikan Hari Ini)

**Step 1.1 — Fix Bug A1** di `NavigationCoordinator.kt:212`:
```kotlin
// SEBELUM:
val momentumBufferMm = 0.5f * linearAccelMmps2 * (tStep * tStep)

// SESUDAH:
val momentumBufferMm = (0.5f * linearAccelMmps2 * (tStep * tStep)).coerceAtMost(150f)
```

**Step 1.2 — Fix Bug B2** di `NavigationCoordinator.kt`:
```kotlin
// Tambahkan field baru:
private var lastAlertObstacleDistanceMm: Int = Int.MAX_VALUE

// Update recordObstacleAlerted():
fun recordObstacleAlerted(...) {
    ...
    lastAlertObstacleDistanceMm = obstacleDistanceMm
}

// Update clearObstacleMemory():
fun clearObstacleMemory() {
    ...
    lastAlertObstacleDistanceMm = Int.MAX_VALUE
}

// Update isSameSemanticState:
val distanceDecreased = lastAlertObstacleDistanceMm - obstacleDistanceMm > 80
val isSameSemanticState = isTranslationallyValid && headingUnchanged 
                          && (currentZone >= lastAlertZone)
                          && !distanceDecreased
```

### Tahap 2 — Perbaikan Pendukung (Setelah Tahap 1 Diuji)

**Step 2.1 — Fix Bug A2** di `TtsAlertManager.kt:197`:
```kotlin
if (isStationary && emaApproachVelocityMmps < 80f) return null  // tambahkan sebelum alertFlag = true
```

**Step 2.2 — Fix Bug B3** di `TtsAlertManager.kt:229`:
```kotlin
if (isMovingForward || emaApproachVelocityMmps > 80f) {
    if (now - lastSpoken > 2500L) return textToSpeak
}
```

### Tahap 3 — Fine-Tuning (Setelah Pengujian Lapangan)

**Step 3.1 — Fix B1**: Turunkan dead zone `< 15` → `< 8` di `NavigationCoordinator.kt:196`  
**Step 3.2 — Fix A3**: Turunkan `EMA_ALPHA` dari `0.45` → `0.30` di `SpatialMappingUtils.kt:27`

---

## Catatan Pengujian

Setelah menerapkan perbaikan, lakukan pengujian lapangan berikut untuk memvalidasi:

| Skenario | Kondisi Lulus |
|----------|---------------|
| User berdiri diam 30 detik, rintangan statis 1.2m | Tidak ada alert yang berbunyi |
| User berdiri diam, objek bergerak cepat mendekat dari 2m | Alert berbunyi dalam <1 detik saat objek < threshold |
| User berjalan pelan ke tembok (0.3 m/s) | Alert pertama <1200mm, alert kedua <800mm (bukan <600mm) |
| User berjalan cepat ke tembok (1.0 m/s) | Alert pertama >1200mm (threshold adaptif naik), ada heartbeat setiap ~2.5 detik |
| User menoleh 90° cepat | Tidak ada alert selama rotasi dan 500ms setelahnya |

---

*Dokumen ini dibuat secara otomatis oleh analisis statis kode pada 2026-08-08. Verifikasi manual terhadap perilaku runtime tetap diperlukan sebelum deployment ke pengguna akhir.*
