# VNetra-Lite — Issue & Changelog Log

> **Base Commit:** ``1f890f3`` — *Final Audit DDD: Fix IMU axis mapping, TTS semantic grammar, clear path delay*
> **Latest Commit:** ``8bb971c`` — *feat: Display dynamic threshold T and vAvg on StreamActivity UI*
> **Audit Standard:** Code-Review & Quality · Doubt-Driven Development · Debugging & Error Recovery · Ponytail Full · Clean Code

---

## ISSUE-006 · ``8bb971c`` — feat: Physics State Exposed to UI
**Tanggal:** 28 Juli 2026
**Kategori:** Feature / Observability

### Latar Belakang
Nilai `vAvg` (kecepatan relatif rintangan) dan `T` (Dynamic Threshold) sebelumnya dikalkulasi sepenuhnya di dalam `StreamService` dan langsung dikonsumsi oleh `TtsAlertManager` tanpa pernah terekspos ke lapisan UI. Hal ini membuat verifikasi perilaku sistem secara visual saat pengujian lapangan tidak mungkin dilakukan, dan menghambat proses analisis akurasi untuk keperluan riset skripsi.

### Perubahan
**`StreamService.kt`**
- Tambah `_physicsFlow: MutableStateFlow<NavigationCoordinator.ObstaclePhysics?>` yang di-emit setiap kali `evaluateObstacles()` selesai memanggil `calculateDynamicThreshold()`.
- Expose sebagai `physicsFlow: StateFlow<...>` untuk dikonsumsi Activity.

**`StreamActivity.kt`**
- Tambah `physicsCollectJob: Job?` sebagai anggota Lifecycle-aware collector baru.
- Koleksi `physicsFlow` dilakukan di dalam `lifecycle.repeatOnLifecycle(STARTED)` menggunakan `Dispatchers.Default` dengan `withContext(Dispatchers.Main)` untuk update UI thread-safe.
- Guard `isDestroyed || isFinishing || isAkhiring` diterapkan sebelum mutasi binding untuk mencegah `IllegalStateException`.
- Cancel job di `cancelAllJobs()` sebelum re-bind.

**`activity_stream.xml`**
- Tambah `tvPhysicsVelocity` (Speed: 0.00 m/s) dan `tvPhysicsThreshold` (Thresh: 0 mm) di dalam panel IMU dengan warna `#80DEEA` sebagai pembeda dari data mentah sensor.

### Nilai Akademis
Nilai Speed dan Thresh yang bergerak real-time di layar menjadi bukti visual langsung bahwa sistem benar-benar mengadaptasi threshold secara kinematis, bukan menggunakan nilai statis. Sangat relevan untuk dokumentasi pengujian skripsi (rekam layar saat uji lapangan).

---

## ISSUE-005 · ``f43b666`` — fix: LatencyLogger Scoped Storage & Buffer Isolation Bug
**Tanggal:** 28 Juli 2026
**Kategori:** Bug Fix / Data Integrity

### Root Cause Analysis
**Bug 1 — Scoped Storage (File Tidak Ditemukan)**
`LatencyLogger` sebelumnya menyimpan file CSV di `getExternalFilesDir(DIRECTORY_DOCUMENTS)`. Pada Android 10+, direktori ini berada di dalam Scoped Storage (`Android/data/<package>/files/`) yang tidak bisa dibrowse oleh pengguna tanpa menggunakan adb atau file manager khusus. Akibatnya, seluruh data latency untuk keperluan riset tidak dapat diakses oleh peneliti pasca-pengujian.

**Bug 2 — Akumulasi Buffer (Data Tidak Representatif)**
`RingBuffer` untuk hw, serial, algo, tts, dan bt tidak di-clear saat `flush()` dipanggil. Akibatnya setiap baris CSV merepresentasikan rata-rata kumulatif sejak awal sesi, bukan rata-rata 1 detik terakhir. Ini menyembunyikan spike latensi transien yang justru paling krusial untuk analisis kualitas sistem di skripsi.

### Perubahan (`LatencyLogger.kt`)
- Path output diubah dari `getExternalFilesDir()` menjadi `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOCUMENTS)`. File kini dapat ditemukan langsung di `/sdcard/Documents/vnetra_latency_*.csv`.
- Tambah WRITE_EXTERNAL_STORAGE permission check.
- Semua RingBuffer di-`.clear()` di dalam `flush()` setelah data selesai ditulis ke baris CSV.

---

## ISSUE-004 · ``4ca2b60`` — fix: Open-Space Pedometer Bounded to Dynamic T
**Tanggal:** 28 Juli 2026
**Kategori:** Logic Bug / Physics Correctness

### Root Cause Analysis
Kondisi reset memori spasial berbasis translasi (`openSpaceWalkFrames++`) sebelumnya membandingkan `dObj` dengan nilai hardcoded. Ini menyebabkan inkonsistensi semantik: seorang pengguna yang berlari kencang (T sekitar 3500 mm) bisa saja berjalan di ruang yang secara sistem dianggap "kosong" padahal masih ada objek dalam jangkauan T-nya.

### Perubahan (`NavigationCoordinator.kt`)
```kotlin
// Sebelum: dibandingkan dengan konstanta hardcoded
if (dObj > HARDCODED_VALUE && aLin > 1.0f && !isHeadRotatingNow)

// Sesudah: dibandingkan dengan T hasil kalkulasi dinamis
if (dObj > T && aLin > 1.0f && !isHeadRotatingNow)
```
Counter `openSpaceWalkFrames` kini hanya bertambah jika `dObj > T`, menjamin koherensi semantis penuh antara sistem peringatan dan sistem memori spasial.

---

## ISSUE-003 · ``43a6bb7`` — fix: SpatialMappingUtils Cap & Wall Column Logic
**Tanggal:** 28 Juli 2026
**Kategori:** Logic Bug / Sensor Consistency

### Root Cause Analysis
**Bug 1 — CLOSE_DIST_MAX Hardcoded**
`SpatialMappingUtils` menggunakan batas maksimum jarak terdekat yang dihardcode (1500 mm). Dengan adanya Dynamic Threshold T yang bisa membesar hingga 4000 mm saat berlari, batas ini menyebabkan rintangan yang berada di antara 1500–4000 mm tidak terdeteksi oleh algoritma pencarian `nearestDistance`, sehingga output `clockDirection` salah/kosong meski T sudah besar.

**Bug 2 — Wall Column Index Off-by-One**
Logika deteksi "Tembok" menggunakan indeks kolom yang salah, menyebabkan beberapa formasi tembok tidak terklasifikasi secara benar.

### Perubahan (`SpatialMappingUtils.kt`)
- `CLOSE_DIST_MAX` dinaikkan menjadi `4000` agar selaras dengan batas atas Dynamic Threshold T.
- Perbaikan indeks kolom pada logika klasifikasi "tembok".

---

## ISSUE-002 · ``f3f42b9`` + ``995350c`` — refactor: Final Massive Audit (SRP, AudioManager EventCallback, Dead Variable Purge)
**Tanggal:** 28 Juli 2026
**Kategori:** Architecture / Refactoring / SRP

### Root Cause Analysis
**SRP Violation — Latency Monitor di StreamService**
`StreamService` menjalankan loop latency monitoring (`delay(200)`) sambil juga menangani WebSocket, UDP, reconnect, dan navigasi, berpotensi menambah context-switching overhead di `Dispatchers.IO`.

**Polling Anti-Pattern — AudioManager**
Deteksi perangkat Bluetooth menggunakan pengecekan manual yang tidak responsif terhadap hot-plug device. Pola usang dan mahal secara CPU.

**Dead Variables — StreamService**
Terdapat beberapa variabel sisa refactor sebelumnya yang tidak pernah dibaca namun tetap menempati heap.

### Perubahan
**`StreamService.kt`**
- Ganti polling AudioManager dengan `AudioDeviceCallback` (event-driven). Callback didaftarkan di `onCreate()` dan di-unregister saat service mati untuk mencegah memory leak.
- Purge semua dead variables dan jobs yang tidak lagi digunakan.

**`NavigationCoordinator.kt`**
- Tambah logika `accumulatedYawSinceAlert` — integrasi nilai Yaw-Rate dari giroskop untuk melacak total rotasi horizontal kepala sejak peringatan terakhir.
- Tambah `openSpaceWalkFrames` sebagai counter pedometer translasi untuk deteksi pergeseran posisi tanpa GPS.
- Sentralisasi `isSameSemanticState`: logika penentuan objek sama/baru (Mini-SLAM) dipindahkan sepenuhnya ke sini.

**`TtsAlertManager.kt`**
- Hapus logika penentuan "apakah ini objek baru?" dari TTS Manager. Manager ini kini hanya menerima flag `isSameSemanticState` dari luar dan bereaksi.
- Sederhanakan kondisi anti-spam menggunakan parameter dari `NavigationCoordinator`.

**`StreamActivity.kt`**
- Hapus `GestureDetector` deprecated.
- Sederhanakan binding update latency panel.

---

## ISSUE-001 · ``adef477`` — fix: TTS Race Condition, UI Double-Mutation, Coroutine Leak
**Tanggal:** 27 Juli 2026
**Kategori:** Bug Fix / Memory Safety / Concurrency

### Root Cause Analysis

**1. UI Double-Mutation (`ToFGridRenderer.kt`)**
- **Issue:** Renderer memodifikasi array `holdover`/`smoothed` yang di-pass by reference dari `SpatialMappingUtils`. Efek: EMA dikalkulasi 2x lebih cepat, desinkronisasi visual vs audio.
- **Fix:** Renderer diubah menjadi read-only murni. Semua operasi matematis dihapus dari kelas ini.

**2. Coroutine Memory Leak (`TtsAlertManager.kt`)**
- **Issue:** `CoroutineScope` TTS tidak di-cancel saat `shutdown()`. Akumulasi zombie coroutines setiap siklus koneksi BT.
- **Fix:** Tambah `scope.cancel()` dalam `shutdown()`.

**3. TTS Race Condition — "Jalan Kosong" Memotong Alert**
- **Issue:** Perintah "jalan kosong" bisa masuk (100-200ms) sebelum `isSpeaking` flag terupdate dari perintah halangan sebelumnya, sehingga TTS halangan tertimpa.
- **Fix:** Drop "jalan kosong" secara absolut jika `System.currentTimeMillis() - lastSpokenTime < 3000L`.

**4. Hysteresis Bug — Spam Alert Tembok**
- **Issue:** Reset flag jarak saat `pitchAngle > 20` menyebabkan alert tembok berulang saat pengguna kembali menatap lurus.
- **Fix:** Sentralisasi filter fisik ke `isAlertPermitted` (boolean) di `NavigationCoordinator`.

**5. SRP Violation — IMU Logic Leak (`StreamService.kt`)**
- **Issue:** Ekstraksi Pitch/Yaw/Roll untuk deteksi rotasi kepala dilakukan di dalam Service.
- **Fix:** Seluruh logika IMU dipindahkan ke `NavigationCoordinator`.

---

## Status Arsitektur Saat Ini

| Komponen | Tanggung Jawab |
|---|---|
| `StreamService` | Orchestrator. TCP/WebSocket/UDP, reconnect, WakeLock, Foreground Notification. |
| `NavigationCoordinator` | Physics Engine. Dynamic Threshold, Mini-SLAM, 3-DoF Odometry. |
| `TtsAlertManager` | TTS Output Consumer. Terima flag, antrean suara, anti-spam. |
| `SpatialMappingUtils` | Sensor Fusion. Analisis matriks ToF, klasifikasi terrain, pemetaan arah jam. |
| `LatencyLogger` | Telemetry. Catat metrik latensi per-interval ke CSV publik di /sdcard/Documents/. |
| `ToFGridRenderer` | UI Renderer Read-Only. Render heatmap grid ToF ke Canvas. |
| `StreamActivity` | Monitor UI. Bind ke Service, tampilkan IMU/ToF/Latency/Physics secara Lifecycle-aware. |
