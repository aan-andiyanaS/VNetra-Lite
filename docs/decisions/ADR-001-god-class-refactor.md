# ADR-001: Refactoring StreamActivity & Resolving God Class (Ponytail Approach)

## Status
Accepted

## Date
2026-07-25


## Context (Konteks & Latar Belakang)
Berdasarkan hasil audit *Clean Code*, kelas `StreamActivity.kt` diidentifikasi memiliki indikasi **God Class**. Blok pengumpulan data (`tofFlow.collect`) membentang ratusan baris kode dengan logika yang tersarang (*nested logic*), mencakup:
- Penanganan UI (User Interface)
- Matematika fusi sensor ToF (Smoothing & Holdover)
- Algoritma evaluasi hambatan dan kalkulasi matriks 
- Eksekusi peringatan suara (*Text-to-Speech*)

Menurut standar arsitektur modern (*Clean Architecture*), logika-logika ini idealnya dipisahkan ke dalam *ViewModel* atau komponen *Use Case* tersendiri agar kode lebih terstruktur. 

## Decision (Keputusan Arsitektur yang Diambil)
Melalui pendekatan **Doubt-Driven Development**, kami menemukan bahwa memindahkan logika `tofFlow.collect` ke dalam *ViewModel* dan mengamatinya menggunakan standar `repeatOnLifecycle(STARTED)` akan memicu efek samping fatal: **sensor dan suara (TTS) akan mati saat layar smartphone dikunci/diredupkan**. Padahal, alat pandu tunanetra mutlak membutuhkan fitur navigasi suara di latar belakang (tanpa harus memegang layar).

Oleh karena itu, berdasarkan prinsip **Ponytail (The Laziest, Simplest, Shortest Path)**, diputuskan untuk:
- **TIDAK** memindahkan logika tersebut ke *ViewModel* maupun memecahnya ke beberapa file (*Zero-New-Files Policy*).
- **Mengekstraksi (Extract Method)** blok kode raksasa tersebut ke dalam fungsi pembantu privat (*private helper functions*) di dalam file `StreamActivity.kt` yang sama.

Fungsi yang telah diekstraksi:
- `processTofData()`: Khusus mengurus inisialisasi dan penghalusan data sensor ToF.
- `evaluateObstacles()`: Khusus memproses deteksi tembok, kemiringan kepala (IMU), dan memicu peringatan suara (TTS).

## Consequences (Konsekuensi & Dampak Perubahan)

### Dampak Positif (Keuntungan)
1. **Clean Code Tercapai:** Metode pengumpulan data utama sekarang sangat bersih dan pendek. Keterbacaan kode meningkat drastis.
2. **Stabilitas Latar Belakang Terjaga:** Dengan tetap mempertahankan komponen di dalam lingkup siklus hidup `Activity` aslinya (berikatan dengan `StreamService`), aplikasi tetap 100% mampu berbunyi dan mendeteksi rintangan meskipun ponsel dikunci di saku pengguna.

### Dampak Negatif (Kelemahan)
- `StreamActivity.kt` masih secara tidak langsung bertindak sebagai pusat seluruh kontrol operasional. Meskipun fungsinya sudah dirapikan, ia masih memiliki *coupling* yang kuat dengan pemrosesan TTS dan Sensor. Hal ini merupakan *trade-off* (kompromi) yang diterima demi stabilitas *Background Task*.

---