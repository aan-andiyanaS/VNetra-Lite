---
title: "Refactor: Massive Cleanup of YOLO/Camera & Code Quality Improvements"
labels: ["refactor", "bug", "documentation"]
---

## Deskripsi Perubahan
Terjadi perubahan arsitektur besar-besaran (Refactoring) pada aplikasi VNetra-Lite yang mengubah aplikasi ini menjadi murni pengolah fusi sensor (ToF & IMU) dan peringatan TTS, menghapus seluruh dependensi visual/kamera berat.

### 🧹 Apa yang Dihapus?
1. **Penghapusan Fitur Kamera & YOLO:** Seluruh file logika yang berkaitan dengan kamera, deteksi objek visual (`YoloDetector.kt`), dan *Overlay* (`BoundingBoxOverlay.kt`) telah dihapus.
2. **Pembersihan Log:** Menghapus dokumen analisis usang (`logcat_full.txt`, `yolo_log.txt`, notebook Jupyter).

### 🔄 Apa yang Diubah/Diganti Nama?
1. **Penamaan Ulang File Inti:**
   - `CameraStreamService.kt` ➡️ `StreamService.kt`
   - `CameraStreamActivity.kt` ➡️ `StreamActivity.kt`
   - `activity_camera_stream.xml` ➡️ `activity_stream.xml`
2. Menyesuaikan *Intent* dan pustaka *ViewBinding* di `MainActivity.kt` dan `DeviceConfigActivity.kt` agar mengarah ke penamaan baru.

### ✨ Apa yang Ditambahkan/Diperbaiki?
1. **Penyuntikan KDocs:** Dokumentasi (*KDocs*) standar Kotlin telah disuntikkan secara otomatis dan komprehensif ke *setiap* fungsi di seluruh *codebase* demi memenuhi standar penulisan akademis/skripsi.
2. **Penambalan Keamanan (Security Patch):** Menambahkan verifikasi *MissingPermission* eksplisit (Android 12+) pada `MainActivity.kt` (Bluetooth Connect) sehingga aplikasi terhindar dari *SecurityException*.
3. **Penambalan Stabilitas (NPE Patch):** Membersihkan penggunaan *Not-Null Assertion* (`!!`) yang berbahaya pada `StreamActivity`, `StreamService`, dan `DeviceConfigActivity`. Menggantinya dengan pembungkus *safe-unboxing* Kotlin (`?.let`, `?:`).
4. **Pembersihan Arsitektur:** Menghapus *dead-code* berupa parameter `ttsAlertManager` yang disuntik namun tidak terpakai di `NavigationCoordinator.kt`.

## Hasil Audit & Verifikasi
- Aplikasi berhasil dikompilasi (`./gradlew assembleDebug`) 100%.
- Lolos dari peringatan fatal Lint (`./gradlew lintDebug`).
- Aplikasi jauh lebih ringan dan hemat sumber daya (CPU) akibat terhapusnya pustaka ML (Machine Learning).

Closes #
