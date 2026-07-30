# Fix: 9 Bug Pasca-Audit Menyeluruh (Race Condition, Dead Code, Label, Storage)

## Deskripsi

Setelah audit menyeluruh baris per baris seluruh codebase VNetra-Lite (Android Kotlin + Firmware C++), ditemukan 34 temuan. Issue ini menutup 9 temuan yang bersifat correctness fix tanpa mengubah logika navigasi asli.

## Perubahan yang Diimplementasikan

### 1. SpatialMappingUtils.kt — Fix #26 + #27
- **#26 Race Condition**: `object SpatialMappingUtils` memiliki mutable state (`emaDistances[]`, `holdoverFrames[]`) yang diakses dari dua thread berbeda — `StreamService` (IO thread) dan `StreamActivity` (Default thread). Tambahkan `@Synchronized` pada `analyzeTerrain()`.
- **#27 `isWall` salah dimensi**: Komentar menyebut "membentang vertikal >= 4 baris", namun implementasi mengecek kolom (`col`) bukan baris (`row`). Diperbaiki ke `distinctRows`.

### 2. NavigationCoordinator.kt — Fix #25 + #23
- **#25 `stationaryFrames` tidak di-reset**: Setelah diam ≥3 detik, `isRestingMode = true` terkunci permanen karena tidak ada reset saat bergerak. Tambah `else stationaryFrames = 0` di branch `aLinMag > 1.0f && !isAccelerating`.
- **#23 DRY violation**: Logika noise-gate IMU duplikasi di `updateMovementState()` dan `isHeadRotating()`. Diekstrak ke private helper `extractFilteredRates()`.

### 3. StreamService.kt — Fix #9 + #12
- **#9 Double `stopStreamAndRelease()`**: `onTaskRemoved()` → `stopStreamAndRelease()` → `stopSelf()` → `onDestroy()` → `stopStreamAndRelease()` kedua kali. Menyebabkan `finalFlush()` dan `speakForce()` ke TTS yang sudah di-shutdown. Hapus dari `onDestroy()`.
- **#12 Dead code**: Dua `when (type)` block — yang pertama berisi body kosong `{ }`. Digabungkan menjadi satu block aktif.

### 4. StreamActivity.kt — Fix #18 + #19 + #22
- **#18 `tvTtsAlert` tidak pernah disembunyikan**: Ditambahkan `postDelayed(hideTtsAlertRunnable, 3000L)` untuk auto-hide setelah 3 detik.
- **#19 `clearStaleSensorDisplay()` tidak membersihkan TTS**: Ditambahkan `tvTtsAlert.visibility = GONE`.
- **#22 Label UI salah**: `"Dyn Accel"` diganti `"Roll (φ)"` karena `imuData[1]` = φ (roll angle) dari Mahony.

### 5. LatencyLogger.kt — Fix #14
- **#14 Deprecated storage API**: `getExternalStoragePublicDirectory()` deprecated API 29 → CSV diam-diam tidak tersimpan di Android 10+. Diganti ke `context.getExternalFilesDir()`.

## Files Changed
- `app/src/main/java/com/airi/vnetra/util/SpatialMappingUtils.kt`
- `app/src/main/java/com/airi/vnetra/util/NavigationCoordinator.kt`
- `app/src/main/java/com/airi/vnetra/service/StreamService.kt`
- `app/src/main/java/com/airi/vnetra/ui/StreamActivity.kt`
- `app/src/main/java/com/airi/vnetra/util/LatencyLogger.kt`
