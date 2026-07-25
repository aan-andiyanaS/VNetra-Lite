---
title: "ADR-044 & Refactor: Massive Pivot to Pure Sensor Fusion & Security Hardening"
labels: ["refactor", "bug", "documentation", "architecture", "security"]
---

# Architecture Decision Record (ADR) & Refactoring Report

## 1. Context (Konteks & Latar Belakang Masalah)
Pada tahap awal pengembangan aplikasi *VNetra-Lite* (Alat Pandu Tunanetra Berbasis Android), sistem dirancang untuk memadukan dua pendekatan:
1. **Sensor Perangkat Keras:** ToF (VL53L5CX) untuk jarak absolut dan IMU (MPU6050) untuk kompensasi gerak.
2. **Kecerdasan Buatan Visual:** Kamera *smartphone* dipadukan dengan model *Machine Learning* YOLO untuk mendeteksi jenis objek (*Object Detection*).

Namun, selama pengujian lapangan dan evaluasi arsitektur, ditemukan beberapa masalah kritis:
- **Overhead Sistem yang Masif:** Pemrosesan YOLOv11 secara *real-time* di *smartphone* menyebabkan *CPU/GPU throttling*, baterai cepat habis, dan latensi (*delay*) tinggi yang sangat berbahaya bagi mobilitas tunanetra secara *real-time*.
- **Pergeseran Fokus Penelitian (Pivot):** Proyek skripsi ini pada akhirnya difokuskan murni pada keandalan pembacaan topografi lingkungan menggunakan matriks sensor ToF yang diproses melalui koneksi *WebSocket* berkecepatan tinggi dari mikrokontroler (ESP32). Modul kamera dan *Overlay* visual menjadi *Dead Code* (kode mati) yang membebani kompilasi proyek.
- **Kerentanan Keamanan (Android 12+):** API pemindaian Bluetooth gagal memenuhi standar privasi `BLUETOOTH_CONNECT` pada sistem operasi Android versi baru, menyebabkan *Force Close* (SecurityException) secara tiba-tiba saat sistem mencoba memindai nama perangkat ESP32.
- **Potensi Crash dari Kode (NPE):** Adanya penggunaan *Not-Null Assertion* (`!!`) yang berbahaya saat melakukan pemisahan (*parsing*) *byte array* data sensor di layanan latar belakang.

## 2. Decision (Keputusan Arsitektur yang Diambil)
Untuk menyelamatkan performa, ketahanan (stabilitas), dan memenuhi standar *Clean Code*, keputusan strategis berikut dieksekusi secara masif:

### A. Pemusnahan Modul Kamera dan YOLO (Removal)
Menghapus lebih dari 10.000 baris kode, pustaka ML, dan berkas analisis lawas, meliputi:
- Penghapusan `YoloDetector.kt` dan *TensorFlow Lite dependencies* dari *build.gradle*.
- Penghapusan `BoundingBoxOverlay.kt` beserta tata letak antarmuka visualnya.
- Penghapusan seluruh berkas dokumentasi usang dan *Jupyter Notebooks* untuk pelatihan YOLO.

### B. Restrukturisasi Penamaan Modul Inti (Renaming)
Mengubah penamaan kelas agar akurat dengan fungsinya (menghilangkan kata "Camera" yang sudah tidak relevan):
- `CameraStreamActivity.kt` ➡️ `StreamActivity.kt`
- `CameraStreamService.kt` ➡️ `StreamService.kt`
- `activity_camera_stream.xml` ➡️ `activity_stream.xml`

### C. Penambalan Keamanan & Stabilitas (Security & NPE Patch)
- Mengimplementasikan fungsi pelindung `hasBleConnectPermission()` di `MainActivity.kt` sebelum aplikasi mencoba mengakses `scanResult.device.name`.
- Menghapus seluruh operator `!!` dari `StreamActivity.kt`, `StreamService.kt`, dan `DeviceConfigActivity.kt`, menggantinya dengan operator aman (*safe-unboxing*) milik Kotlin (`?.let` dan `?:`).

### D. Pemenuhan Standar Dokumentasi Akademis
- Menyuntikkan blok *Kotlin Documentation* (KDocs) ekstensif ke *setiap* fungsi di seluruh proyek. Hal ini dilakukan demi memenuhi pedoman penulisan skripsi yang mengharuskan penjelasan fungsional detail untuk bahan laporan akademis.

## 3. Consequences (Konsekuensi & Dampak Perubahan)

### Dampak Positif (Keuntungan)
1. **Performa Instan:** Latensi jaringan untuk memproses dan menerjemahkan data matriks sensor 8x8 kini menjadi sangat ringan dan hampir instan, tanpa ada siklus CPU yang "dirampok" oleh model AI.
2. **Zero-Crash di Android Modern:** Aplikasi kini dapat berjalan sangat stabil tanpa tertutup paksa di perangkat Android 12, 13, maupun 14 berkat pembaruan manajemen izin (*permissions*).
3. **Keterbacaan Kode (Maintainability):** Struktur proyek sangat bersih, memungkinkan pengembangan fusi sensor spasial lebih lanjut tanpa terdistraksi serpihan kode visual.

### Trade-off (Kekurangan yang Diterima Sementara)
- **Gejala "God Class" pada `StreamActivity`:** Kelas ini saat ini berukuran hampir 1.000 baris, mengelola antarmuka (UI), matematika kalkulasi jarak ToF, dan pemanggilan peringatan suara (TTS) di dalam satu siklus blok `Flow.collect` yang raksasa. 
- Keputusan untuk *tidak memecah kelas ini sekarang juga* diambil secara **sengaja**; perombakan arsitektur pemisahan ke *ViewModel* berpotensi mematikan fitur peringatan TTS ketika layar *smartphone* diredupkan (masalah siklus hidup Android). Ini adalah kompromi yang diterima (demi keselamatan tunanetra) hingga ada perbaikan sistem *Background Service* menyeluruh.

---
*Laporan ini dibuat otomatis menggunakan pedoman keterampilan /documentation-and-adrs.*
