Berdasarkan hasil audit mendalam (Doubt-Driven Development & SRP sweep), ditemukan beberapa _silent bugs_ dan pelanggaran arsitektur yang tersisa setelah commit `1f890f3`.

### Root Cause Analysis & Fixes:

1. **UI Double-Mutation Bug (`ToFGridRenderer.kt`)**
   - **Issue:** Renderer UI secara ilegal memodifikasi (mutasi) data array `holdover` dan `smoothed` secara mandiri. Karena `SpatialMappingUtils` di background service juga melakukan smoothing pada array yang sama (passed by reference), perhitungan EMA dieksekusi 2x lebih cepat dari seharusnya. Ini menyebabkan desinkronisasi jarak visual vs audio.
   - **Fix:** Renderer diubah menjadi 100% _read-only_ (pasif). Dihapus semua operasi matematika/mutasi array dari kelas ini.

2. **TTS Coroutine Memory Leak (`TtsAlertManager.kt`)**
   - **Issue:** _Coroutine Scope_ milik TTS tidak pernah di-cancel saat fungsi `shutdown()` dipanggil. Ini menyebabkan akumulasi _zombie coroutines_ di RAM setiap kali sesi bluetooth diputus-sambung.
   - **Fix:** Menambahkan `scope.cancel()` pada `shutdown()`.

3. **TTS Race Condition: "Jalan Kosong" Memotong Alert (`TtsAlertManager.kt`)**
   - **Issue:** Ada jeda asinkron (~100-200ms) saat mesin TTS dihidupkan. Jika perintah "jalan kosong" masuk tepat setelah perintah halangan, namun TTS halangan belum sempat mengubah state `isSpeaking` menjadi true, antrean akan tertimpa (_flush_) dan pesan halangan terpotong.
   - **Fix:** Validasi ketat pada `speakAdd()`. Jika selisih waktu dari `lastSpokenTime` (alert halangan terakhir) belum melebihi 3 detik, perintah jalan kosong akan di-drop/ditolak secara absolut.

4. **Hysteresis Bug: Spam Alert Tembok (`NavigationCoordinator.kt`)**
   - **Issue:** Saat user mendongak > 20 derajat (melihat plafon), alert tembok dibisukan. Namun sistem lama justru me-reset _flag_ jarak sepenuhnya, sehingga saat user kembali menatap lurus, alert tembok diulang dari awal (spamming).
   - **Fix:** Sentralisasi filter fisik ke output boolean `isAlertPermitted` di `NavigationCoordinator`. Evaluasi batas jarak (`D_RESET`) di TTS Manager kini menggunakan parameter ini untuk menunda _flag reset_, mencegah spam.

5. **SRP Violation: IMU Logic Leak (`StreamService.kt`)**
   - **Issue:** Service masih melakukan ekstraksi raw IMU (Pitch/Yaw/Roll) untuk mendeteksi rotasi kepala.
   - **Fix:** Logic IMU dipindahkan seutuhnya ke `NavigationCoordinator`. `StreamService` kini murni berfungsi sebagai _Orchestrator_.
