# Issue: Bug Fixes Session — 2026-08-09

**Base Commit:** `7216f0e` — *Refactor: standardize variable names to clean code*  
**Branch:** `feature/kinematics-tuning`  
**Status:** Unstaged (belum di-commit)  
**Standar:** `/code-review-and-quality` · `/doubt-driven-development` · `/debugging-and-error-recovery` · `/documentation-and-adrs`

---

## Ringkasan Perubahan

| File | Bug ID | Kategori | Baris Berubah |
|------|--------|----------|---------------|
| `NavigationCoordinator.kt` | BUG-03 | 🔴 Critical swap | L:51–54 |
| `NavigationCoordinator.kt` | BUG-08 | 🟠 Logic | L:249–252 |
| `NavigationCoordinator.kt` | BUG-17 | 🟠 New find | L:201–213 |
| `SessionDataLogger.kt` | BUG-02 | 🔴 Crash risk | L:129–139 |
| `TtsAlertManager.kt` | BUG-05 | 🟡 Inkonsistensi | L:34–42 |
| `SpatialMappingUtils.kt` | NEW | 🟠 Presisi | L:76–136 |
| `SpatialMappingUtils.kt` | Rename | 🟢 Clarity | L:40, 131 |
| `StreamService.kt` | Rename | 🟢 Clarity | L:797 |

---

## BUG-03 — `pitchRate` dan `rollRate` Tertukar

**File:** `NavigationCoordinator.kt` L:51–54  
**Severity:** 🔴 HIGH — menyebabkan guard kepala mendeteksi axis yang salah

### Context

Firmware ESP32 mengirim payload IMU dalam urutan berikut (divalidasi dari `firmware-vnetra.ino` L:870–884):

```
[0]=θ pitch angle    [1]=φ roll angle
[2]=wx_corr_deg      ← rotasi sumbu X = PITCH (angguk maju/mundur)
[3]=wy_corr_deg      ← rotasi sumbu Y = ROLL  (miring kiri/kanan)
[4]=wz_corr_deg      ← rotasi sumbu Z = YAW   (menoleh)
```

### Masalah

```kotlin
// SEBELUM — SALAH:
private fun extractFilteredRates(imuData: FloatArray?): Triple<Float, Float, Float> {
    return Triple(
        (imuData?.getOrElse(3) { 0f } ?: 0f).denoised(), // "pitchRate" ← [3]=wy=ROLL
        (imuData?.getOrElse(2) { 0f } ?: 0f).denoised(), // "rollRate"  ← [2]=wx=PITCH
        (imuData?.getOrElse(4) { 0f } ?: 0f).denoised()  // yawRate     ← [4]=wz ✓
    )
}
```

**Dampak:** `isAlertPermitted` memeriksa `pitchAngle > 25f` vs `pitchRate` yang
sebenarnya adalah `wy_corr` (roll rate). Guard "menunduk" tidak berfungsi dengan benar.

### Fix

```kotlin
// SESUDAH — BENAR:
return Triple(
    (imuData?.getOrElse(2) { 0f } ?: 0f).denoised(), // pitchRate = wx_corr_deg = [2] (angguk)
    (imuData?.getOrElse(3) { 0f } ?: 0f).denoised(), // rollRate  = wy_corr_deg = [3] (miring)
    (imuData?.getOrElse(4) { 0f } ?: 0f).denoised()  // yawRate   = wz_corr_deg = [4] ✓
)
```

---

## BUG-08 — Pitch Gate Hanya Berlaku untuk Tipe "tembok"

**File:** `NavigationCoordinator.kt` L:249–252  
**Severity:** 🟠 HIGH — alert "objek" bisa muncul saat pengguna menunduk ke lantai

### Masalah

```kotlin
// SEBELUM — pitch gate HANYA untuk "tembok":
val isStaticObst = objectLabel == "tembok"
val isAlertPermitted = !isHeadRotatingNow && !isRestingMode && !(isStaticObst && pitchAngle > 20f)
// → Jika objectLabel = "objek", pitch gate TIDAK aktif sama sekali
// → Lantai terdeteksi sebagai "objek" (hanya beberapa baris vertikal)
// → User menunduk → alert "objek di arah 12" tetap berbunyi
```

### Fix

```kotlin
// SESUDAH — pitch gate berlaku untuk semua tipe:
// Threshold 25° dipilih: menunduk natural berjalan (<20°) tidak memblokir,
// menunduk aktif melihat lantai (>25°) memblokir semua alert termasuk "objek".
val isAlertPermitted = !isHeadRotatingNow && !isRestingMode && !(pitchAngle > 25f)
```

**Rationale threshold 25°:** Referensi ergonomi pejalan kaki menunjukkan postur berjalan
normal menghasilkan pitch ~10–18°. Gap 7° antara 18° (batas atas normal) dan 25° (trigger)
cukup untuk menghindari false blocking.

---

## BUG-17 — EMA Velocity Spike dari Objek Transient (Bug Baru)

**File:** `NavigationCoordinator.kt` L:201–213  
**Severity:** 🟠 HIGH — false alert ~400ms setelah tangan atau benda sekilas melintas sensor

### Root Cause

Ketika tangan melintas di depan sensor ToF:

```
Frame T0: d_obj = 1500mm (obstacle nyata)
Frame T1: tangan masuk → d_obj = 250mm
  dDelta = 1500 - 250 = 1250mm
  rawApproachVelocityMmps = 1250/0.066 ≈ 18939 → clamped ke 2000mm/s  ← SPIKE
  emaVelocityStateMmps = 0.4×2000 + 0.6×100 = 860mm/s

Frame T2: tangan hilang → d_obj = 1500mm kembali
  rawApproachVelocityMmps = 0 (jarak meningkat)
  emaVelocityStateMmps = 0.4×0 + 0.6×860 = 516mm/s  ← EMA masih tinggi!
  T = 1000 + (516 × 1.3) = 1671mm
  → d_obj(1500mm) < T(1671mm) → FALSE ALERT
```

EMA butuh **~400ms** (5–6 frame @15Hz) untuk decay ke nilai normal.

### Fix

```kotlin
// Spike Rejection: jika raw velocity melonjak >800mm/s di atas EMA saat ini
// dalam satu frame ToF, anggap sebagai objek transient — abaikan frame ini.
// Threshold 800mm/s: pendekatan nyata <500mm/s per-frame @15Hz,
// sedangkan spike transient selalu >1000mm/s.
val velocityJump = rawApproachVelocityMmps - emaVelocityStateMmps
val filteredVelocity = if (velocityJump > 800f) emaVelocityStateMmps else rawApproachVelocityMmps

emaVelocityStateMmps = (0.4f * filteredVelocity) + (0.6f * emaVelocityStateMmps)
emaApproachVelocityMmps = emaVelocityStateMmps
lastVRaw = rawApproachVelocityMmps  // simpan raw untuk logging CSV, bukan filteredVelocity
```

---

## BUG-02 — `csvWriter` Tidak Di-null Setelah `finalFlush()`

**File:** `SessionDataLogger.kt` L:129–139  
**Severity:** 🔴 CRITICAL — potensial `IOException: Stream closed` pada shutdown

### Masalah

```kotlin
// SEBELUM — csvWriter masih non-null setelah close():
fun finalFlush() {
    try {
        csvWriter?.flush()
        csvWriter?.close()   // ← close tapi tidak null
    } catch (e: Exception) { ... }
    // csvWriter non-null! Jika record() dipanggil setelahnya → IOException
}
```

### Fix

```kotlin
// SESUDAH — finally block menjamin null:
fun finalFlush() {
    try {
        csvWriter?.flush()
        csvWriter?.close()
        Log.i(TAG, "SessionDataLogger closed. Total frames: $frameCount")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to close SessionDataLogger", e)
    } finally {
        csvWriter = null  // BUG-02 fix: null setelah close agar record() tidak IOException
    }
}
```

---

## BUG-05 — `D_W0` Tidak Selaras dengan `baseWarningDistanceMm`

**File:** `TtsAlertManager.kt` L:42  
**Severity:** 🟡 MEDIUM — selama ~2.5 detik warmup, threshold 20% lebih sensitif dari desain

### Masalah

```kotlin
// SEBELUM:
const val D_W0 = 1200  // ← tidak selaras dengan baseWarningDistanceMm = 1000
// Selama warmup Mahony AHRS, sistem menggunakan 1200mm bukan 1000mm
```

### Fix

```kotlin
// SESUDAH:
const val D_W0 = 1000  // mm — selaras dengan baseWarningDistanceMm di NavigationCoordinator
```

---

## NEW — Presisi Arah Jam: Nearest-Cell vs Centroid

**File:** `SpatialMappingUtils.kt` L:76–136  
**Severity:** 🟠 HIGH — arah yang dilaporkan bisa berlawanan dengan bahaya nyata

### Masalah Sebelumnya

Arah jam dihitung dari **centroid rata-rata** semua danger cells:

```kotlin
var sumCol = 0
// ...
sumCol += (i % 8)  // semua sel bobotnya sama
// ...
val centroidCol = (sumCol.toFloat() / count).roundToInt()
val clockDir = getColumnClockDirection(centroidCol)
```

**Contoh failure case — dinding diagonal:**
```
Col:  0    1    2    3    4    5    6    7
     300  350  400  500  550  580  600  620   (row 3)
```
- `nearestDist` = 300mm, di `col 0` (kiri)
- Centroid danger cells: (0+1+2+3+4+5+6+7)/8 = 3.5 → `col 4` → **arah 12**
- User mendengar: *"tembok, arah 12"* — padahal bahaya terdekat ada di **kiri (arah 10)**!

### Fix

Track kolom sel terdekat di pass pertama, gunakan sebagai direction:

```kotlin
// Pass 1 — tambah tracking nearestCol:
var nearestDist = Int.MAX_VALUE
var nearestCol  = 4  // default tengah

if (dist in CLOSE_DIST_MIN..CLOSE_DIST_MAX && dist < nearestDist) {
    nearestDist = dist
    nearestCol  = i % 8  // rekam kolom sel terdekat ← BARU
}

// Step 4 — gunakan nearestCol, bukan centroid:
// Centroid bisa menunjuk berlawanan dari bahaya nyata pada obstacle diagonal/multi-titik.
// Sel terdekat = titik paling kritis untuk navigasi → selalu prioritaskan arahnya.
val clockDir = getColumnClockDirection(nearestCol)
```

**`sumCol` dihapus** karena tidak lagi digunakan (dead code setelah fix).

### Perbandingan 3 Skenario

| Skenario | Sebelum (centroid) | Sesudah (nearest-col) |
|----------|-------------------|----------------------|
| Dinding diagonal (dekat di kiri) | arah 12 ❌ | arah 10 ✅ |
| Dua objek (kiri 500mm, kanan 420mm) | arah 12 ❌ | arah 1 ✅ |
| Dinding lurus rata | arah 12 ✅ | arah 12 ✅ (tidak regresi) |

---

## Rename: "halangan" → "objek"

**Alasan:** Label "halangan" mengandung asumsi semantik (ada yang menghalangi),
sedangkan "objek" lebih netral dan sesuai terminologi laporan BAB III.

| File | Baris | Perubahan |
|------|-------|----------|
| `SpatialMappingUtils.kt` | L:40 | komentar data class |
| `SpatialMappingUtils.kt` | L:131 | string literal classifier |
| `NavigationCoordinator.kt` | L:251 | komentar BUG-08 |
| `StreamService.kt` | L:797 | fallback label |

---

## Bug yang Masih Belum Diperbaiki

| ID | File | Deskripsi | Prioritas |
|----|------|-----------|----------|
| BUG-01 | `SpatialMappingUtils.kt` | Singleton race condition pada `reset()` | 🔴 Critical |
| BUG-04 | `SpatialMappingUtils.kt` | `maxDangerDist = nearestDist + 300` flat tolerance | 🟠 High |
| BUG-07 | `WifiInfo.kt` / `BleManager.kt` | SSID separator `|` konflik dengan SSID berisi `|` | 🟡 Medium |
| BUG-06 | `StreamService.kt` | `updateMovementState()` call site timing | 🟡 Medium |

---

## Suggested Commit Message

```
fix(navigation): resolve 5 audit bugs + precision improvements

BUG-03: Fix pitchRate/rollRate index swap in extractFilteredRates()
        [2]=wx_corr=pitch, [3]=wy_corr=roll (validated from firmware payload)
BUG-08: Extend pitch gate to all object types (was tembok-only), threshold 20->25deg
BUG-17: Add spike rejection to EMA velocity (velocityJump > 800mm/s = transient object)
BUG-02: Add finally{csvWriter=null} after finalFlush() to prevent IOException
BUG-05: Align D_W0=1000 with baseWarningDistanceMm (was 1200, 20% mismatch)

Precision: SpatialMappingUtils uses nearest-cell column for clock direction
instead of unweighted centroid — fixes wrong direction on diagonal obstacles.

Rename: 'halangan' -> 'objek' across 4 files for semantic clarity
```
