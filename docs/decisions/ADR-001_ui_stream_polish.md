# ADR-001: Stream Activity UI Polish and UX Enhancements

## Status
Accepted

## Date
2026-07-26

## Context
`StreamActivity` merupakan antarmuka visual utama untuk aplikasi VNetra-Lite. Selama pengujian dan persiapan demonstrasi, beberapa masalah UI (antarmuka) dan UX (pengalaman pengguna) ditemukan:
1. **Indikator Arah Jam:** Label teks angka (10, 11, 12, 1, 2) memakan ruang layar dan terlihat berantakan. Pengguna lebih memilih untuk hanya mengandalkan garis pembatas (divider) untuk memetakan area ToF ke arah jam.
2. **Interaksi Geser (Swipe):** Gestur geser untuk memunculkan tombol "Akhiri" tidak memiliki transisi yang halus, sehingga interaksi terasa kaku dan mendadak. Selain itu, teks instruksinya ("< geser") kurang komunikatif.
3. **Inkonsistensi Tipografi:** Header IMU menggunakan font standar, sedangkan data IMU menggunakan font monospace. Selain itu, ukuran font panel Latency Monitor (9sp), warna (#AAAAAA), dan padding latar belakang tidak sama dengan panel Data IMU, sehingga terlihat asimetris dan kurang rapi.
4. **Sisa Kode Pengembangan:** Teks Latency Monitor berisi kata "(SKRIPSI)", yang perlu dihapus agar aplikasi siap untuk tahap produksi/demonstrasi.

## Decision
Kami memutuskan untuk merombak elemen UI pada `StreamActivity` melalui penyesuaian XML dan Kotlin yang terarah, daripada memasukkan library baru atau mengubah arsitektur tata letak secara besar-besaran.

Implementasi spesifik:
- **Garis Pembatas (Grid Dividers):** Mengganti indikator arah jam berbasis teks dengan garis pembatas hijau penuh (`#AA00C853`) yang terikat (constrained) dari atas hingga bawah grid ToF, menggunakan distribusi bobot tata letak `1-2-2-2-1`.
- **Transisi Geser (Swipe):** Mengubah wadah lencana (badge) dari `FrameLayout` menjadi `LinearLayout` dan mengimplementasikan `TransitionManager.beginDelayedTransition()` di `StreamActivity.kt` untuk memunculkan tombol secara halus dan memudar.
- **Sinkronisasi Tipografi & Gaya:** Menerapkan `android:fontFamily="monospace"` di seluruh header telemetri. Menyelaraskan ukuran teks Latency Monitor (10sp), warna teks (`#FFFFFF`), warna latar belakang (`#CC000000`), dan padding agar persis sama dengan panel Data IMU.

## Alternatives Considered

### 1. Menghapus Indikator Arah Jam Sepenuhnya
- **Pros:** Ruang layar maksimal untuk grid ToF.
- **Cons:** Pengguna akan kehilangan referensi orientasi spasial (arah hadap).
- **Rejected:** Garis pembatas grid yang melintang penuh (full-height) memberikan konteks spasial yang diperlukan tanpa menimbulkan kekacauan teks.

### 2. Menggunakan `MotionLayout` Android untuk Animasi Geser
- **Pros:** Animasi deklaratif yang sangat dapat disesuaikan.
- **Cons:** Membutuhkan perombakan XML yang signifikan dan meningkatkan kompleksitas tata letak hanya untuk sebuah efek kemunculan (fade/slide) yang sederhana.
- **Rejected:** `TransitionManager.beginDelayedTransition()` memberikan tingkat kehalusan yang persis dibutuhkan hanya dengan satu baris kode Kotlin dan nol perombakan tata letak.

## Consequences
- UI sekarang terlihat sangat profesional, simetris, dan bernuansa "kokpit pesawat", yang mana sangat ideal untuk demonstrasi.
- Basis kode menjadi lebih bersih, dengan string yang siap produksi (tidak ada lagi label "(SKRIPSI)").
- Pendekatan `TransitionManager` memastikan aplikasi tetap ringan tanpa menarik dependensi animasi yang berat.
- Penambahan panel telemetri di masa mendatang harus mematuhi gaya yang telah ditetapkan (monospace, 10sp, teks #FFFFFF, latar belakang #CC000000) untuk menjaga simetri visual.

## Git Commits (Reference)
- `1e903e5`: Sinkronisasi tipografi dan pembersihan string.
- `ced8fc0`: Sentuhan simetri final (padding, background, color) untuk Latency Monitor.
