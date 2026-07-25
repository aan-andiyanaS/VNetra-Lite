---
title: "Refactor: Massive Cleanup of YOLO/Camera & Code Quality Improvements"
labels: ["refactor", "bug", "documentation", "architecture"]
---

# Architecture Decision & Refactoring Report

## Context (Konteks & Latar Belakang)
Aplikasi VNetra-Lite pada awalnya dikembangkan dengan menyertakan modul kamera dan deteksi objek visual berbasis model YOLO (`YoloDetector.kt`). Namun, seiring berjalannya proyek, fokus utama sistem bergeser sepenuhnya pada pengolahan fusi sensor perangkat keras (ToF VL53L5CX dan IMU MPU6050) untuk mendeteksi rintangan spasial. Keberadaan modul kamera/YOLO membebani CPU, meningkatkan kompleksitas kode (seperti di dalam `CameraStreamActivity`), dan berpotensi memicu *crash* akibat beban memori tinggi, padahal fitur visual tersebut sudah tidak digunakan lagi (Dead Code). 

Selain itu, berdasarkan hasil audit *Static Analysis (Lint)* dan tinjauan *Clean Code*:
- Terdapat penggunaan *Not-Null Assertion* (`!!`) yang berbahaya saat mengurai data sensor.
- Terdapat pemanggilan pembacaan Bluetooth (`scanResult.device.name`) yang melanggar aturan izin ketat di Android 12+ (Missing Permissions).

## Decision (Keputusan)
1. **Pemusnahan Modul Kamera/ML:** Menghapus seluruh logika, dependensi, *layout*, dan berkas log yang berkaitan dengan kamera dan YOLO secara permanen.
2. **Penyederhanaan Arsitektur:** Mengubah nama kelas-kelas utama agar mencerminkan tujuan spesifiknya saat ini (contoh: `CameraStreamActivity.kt` ➡️ `StreamActivity.kt`).
3. **Penerapan DDD (Doubt-Driven Development) untuk Bug Fixes:** 
   - Menambahkan pembungkus *safe-unboxing* (`?.let`, `?:`) menggantikan paksaan `!!`.
   - Mengimplementasikan `hasBleConnectPermission()` untuk memvalidasi keamanan Bluetooth sebelum mengakses perangkat, menambal celah *SecurityException*.
4. **Pemenuhan Syarat Akademis:** Menyuntikkan KDocs standar pada setiap fungsi secara otomatis menggunakan skrip pemrosesan agar memenuhi standar dokumentasi penulisan skripsi.

## Consequences (Konsekuensi)
### Dampak Positif
- **Performa Maksimal:** Beban memori dan komputasi CPU turun drastis karena *Machine Learning* lokal tidak lagi dimuat ke dalam memori aplikasi.
- **Stabilitas (Zero-Crash):** Menghindari *Force Close* tiba-tiba di berbagai perangkat Android modern (12/13/14) akibat hak akses Bluetooth dan data null sesaat pada jaringan *socket*.
- **Keterbacaan (Clean Code):** Basis kode (*codebase*) menyusut puluhan ribu baris (lebih dari 10.000 baris kode lawas dihapus), menjadikannya lebih mudah dirawat dan dinavigasi oleh pengembang atau agen AI di masa depan.

### Dampak Negatif / Trade-off
- **God Class:** Kelas `StreamActivity` saat ini masih mengurus UI, pengumpulan *flow* (ToF & IMU), dan peringatan suara (TTS) sekaligus. Keputusan untuk memisahkannya (Refaktor MVP) ditunda agar fitur suara latar belakang TTS tidak terganggu oleh siklus hidup aplikasi (Lifecycle), namun hal ini menjadi target optimasi arsitektur di masa depan.

Closes #
