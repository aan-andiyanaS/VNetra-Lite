# Issue Log: Alert Paradox Fix — VNetra-Lite

**Tanggal:** 2026-08-08  
**Branch:** `feature/kinematics-tuning`  
**Status:** ✅ Diterapkan  
**Standar:** `/ponytail full` · `/clean-code` · `/debugging-and-error-recovery`

---

## Deskripsi Masalah

Sistem menunjukkan dua gejala berlawanan secara bersamaan:

1. **Spam peringatan saat diam** — alert berbunyi berulang meski pengguna tidak bergerak dan tidak ada rintangan yang mendekati.
2. **Delay atau bungkam saat maju ke tembok** — alert pertama muncul terlambat, alert lanjutan teredam sepenuhnya hingga pengguna sudah sangat dekat.

Keduanya bersumber dari tiga konstanta keliru dan satu logika semantik yang terlalu agresif.

---

## Perubahan yang Diterapkan

### 1. `NavigationCoordinator.kt`

#### [FIX-1] `baseWarningDistanceMm`: 1200mm → 1000mm

**Alasan:** Batas 1.2 meter terlalu konservatif dan membuat hampir semua objek di sekitar pengguna masuk zona bahaya. Batas 1.0 meter adalah standar umum yang lebih selaras dengan radius aman navigasi pejalan kaki.

```diff
- baseWarningDistanceMm: Int = 1200  // jarak ergonomi tongkat putih
+ baseWarningDistanceMm: Int = 1000  // batas peringatan 1 meter (d_0 dalam formula SSD)
```

---

#### [FIX-2] `humanReactionTimeSec` (2.5s) → `perceptionReactionTimeSec` (1.3s)

**Alasan:** Nilai 2.5 detik adalah standar AASHTO untuk kendaraan bermotor. BAB II 2.8 skripsi mendefinisikan rentang **1.1–1.5 detik** untuk pengguna ETA tunanetra berdasarkan Kovács & Nagy [44]. Nilai 1.3 detik dipilih sebagai titik tengah rentang empiris tersebut. Dampak langsung: komponen `v_rel × t_r` dalam formula SSD mengecil, sehingga threshold tidak lagi membengkak secara berlebihan saat pengguna bergerak normal.

**Dampak matematis:**
- Sebelum: pengguna jalan santai 500mm/s → `v × t = 500 × 2.5 = 1250mm` ditambah ke threshold
- Sesudah: pengguna jalan santai 500mm/s → `v × t = 500 × 1.3 = 650mm` — lebih realistis

```diff
- val humanReactionTimeSec = 2.5f  // AASHTO — standar kendaraan
+ // ponytail: 1.3s = midpoint [1.1, 1.5] dari Kovács & Nagy [44]; AASHTO 2.5s adalah untuk kendaraan.
+ val perceptionReactionTimeSec = 1.3f
```

---

#### [FIX-3] Tambah tracking `lastAlertObstacleDistanceMm` + perbaikan `isSameSemanticState`

**Masalah yang diperbaiki:** `isSameSemanticState` sebelumnya hanya membandingkan *zone bucket* (ZONE_DEKAT / ZONE_SEDANG / ZONE_JAUH). Zone SEDANG mencakup rentang 600–1800mm — pengguna bisa melangkah **1.2 meter penuh** tanpa alert kedua berbunyi karena zone-nya "sama".

**Fix:** Tambah satu field `lastAlertObstacleDistanceMm` untuk melacak jarak absolut saat alert terakhir. Jika jarak berkurang lebih dari 80mm (≈ satu pijakan kaki) sejak alert terakhir, situasi dianggap "baru" dan alert diizinkan kembali.

```diff
+ private var lastAlertObstacleDistanceMm: Int = Int.MAX_VALUE
```

```diff
  fun recordObstacleAlerted(...) {
      ...
+     lastAlertObstacleDistanceMm = obstacleDistanceMm
  }
  
  fun clearObstacleMemory() {
      ...
+     lastAlertObstacleDistanceMm = Int.MAX_VALUE
  }
```

```diff
- val isSameSemanticState = isTranslationallyValid && headingUnchanged && (currentZone >= lastAlertZone)
+ // ponytail: 80mm ≈ 1 pijakan kaki; jika user maju sejauh ini sejak alert terakhir, anggap situasi baru.
+ val distanceDecreasedSignificantly = lastAlertObstacleDistanceMm - obstacleDistanceMm > 80
+ val isSameSemanticState = isTranslationallyValid && headingUnchanged
+                           && (currentZone >= lastAlertZone)
+                           && !distanceDecreasedSignificantly
```

---

### 2. `TtsAlertManager.kt`

#### [FIX-4] Guard `isStationary` di alert pertama

**Masalah yang diperbaiki:** Alert pertama (`!alreadyAlerted`) tidak punya pengecekan apakah user benar-benar diam. Akibatnya, lonjakan threshold dari `momentumBuffer` (yang belum di-fix) masih bisa memicu alert pertama tanpa ada objek yang bergerak mendekat.

```diff
  if (isSameSemanticState) { return null }
  
+ // ponytail: blok alert pertama jika user diam & tidak ada objek yang benar-benar mendekat.
+ if (isStationary && emaApproachVelocityMmps < 80f) {
+     Log.d(TAG, "Muted: stationary user, no approaching object (ema=${emaApproachVelocityMmps}mm/s)")
+     return null
+ }
```

---

#### [FIX-5] Heartbeat berbasis `emaApproachVelocityMmps` sebagai fallback IMU

**Masalah yang diperbaiki:** Heartbeat 2.5 detik sebelumnya hanya aktif jika `isMovingForward = true`. Namun `isMovingForward` membutuhkan `aLinMag > 1.0 m/s²` — threshold yang sering tidak tercapai saat pengguna berjalan pelan di atas karpet atau permukaan empuk. Akibatnya, bahkan saat pengguna terus maju ke tembok dan sensor ToF mendeteksi objek mendekat, tidak ada alert lanjutan.

```diff
- if (isMovingForward) {
-     if (now - lastSpoken > 2500L) { return textToSpeak }
- }
+ // ponytail: fallback ke ema > 80f jika IMU tidak deteksi langkah (jalan pelan/karpet).
+ if (isMovingForward || emaApproachVelocityMmps > 80f) {
+     if (now - lastSpoken > 2500L) {
+         Log.d(TAG, "Heartbeat alert: isMovingForward=$isMovingForward ema=${emaApproachVelocityMmps}mm/s")
+         return textToSpeak
+     }
+ }
```

---

## Perubahan yang TIDAK Diterapkan (Sengaja Ditunda)

| Item | Alasan |
|------|--------|
| `momentumBufferMm.coerceAtMost(150f)` | Belum dieksekusi per permintaan pengguna; akan dievaluasi setelah pengujian lapangan fix 1–5 |
| `EMA_ALPHA` 0.45 → 0.30 di `SpatialMappingUtils.kt` | SpatialMappingUtils memiliki bug terpisah yang perlu diaudit sendiri sebelum fine-tuning alpha |

---

## Dampak Perubahan terhadap Formula SSD

**Formula sesudah fix (selaras dengan BAB II 2.8):**

$$T = \min(4000, \underbrace{1000}_{d_0} + \underbrace{(v_{rel} \times 1.3)}_{d_R} + \underbrace{\frac{1}{2} a_{lin} \times 0.632^2}_{d_B})$$

**Perbandingan nilai threshold untuk skenario tipikal:**

| Skenario | Threshold Sebelumnya | Threshold Sesudah |
|----------|---------------------|-------------------|
| Diam, tidak ada objek | 1200 + 0 + 0 = **1200mm** | 1000 + 0 + 0 = **1000mm** |
| Jalan santai 500mm/s | 1200 + 1250 + var = **≥2450mm** | 1000 + 650 + var = **≥1650mm** |
| Jalan cepat 1000mm/s | 1200 + 2500 + var = **≥3700mm** | 1000 + 1300 + var = **≥2300mm** |

Penurunan threshold secara signifikan mengurangi false positive saat diam maupun saat bergerak lambat.

---

## Verification Checklist

- [ ] Berdiri diam 30 detik, rintangan statis 1.5m → tidak ada alert
- [ ] Berdiri diam, orang berjalan mendekati dari 2m → alert dalam <3 detik
- [ ] Jalan pelan ke tembok → alert pertama <1000mm, alert kedua tidak lebih dari 80cm kemudian
- [ ] Jalan normal ke tembok → heartbeat setiap ~2.5 detik
- [ ] Menoleh cepat → tidak ada alert selama + 500ms setelah rotasi

---

## File yang Diubah

| File | Baris Diubah |
|------|-------------|
| [NavigationCoordinator.kt](file:///e:/Project/Skripsi/VNetra-Lite/app/src/main/java/com/airi/vnetra/util/NavigationCoordinator.kt) | 108–111 (field), 125, 139, 154, 205–206, 214, 259–265 |
| [TtsAlertManager.kt](file:///e:/Project/Skripsi/VNetra-Lite/app/src/main/java/com/airi/vnetra/util/TtsAlertManager.kt) | 207–213, 228–237 |
