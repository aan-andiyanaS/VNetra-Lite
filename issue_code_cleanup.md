# Perbaikan Dead Code dan Performa Berdasarkan Audit Kualitas Kode

## Latar Belakang
Audit kualitas kode (_code-review-and-quality_, _doubt-driven-development_, _ponytail full_) menemukan beberapa masalah yang tidak memengaruhi fungsionalitas, tetapi menurunkan keterbacaan dan efisiensi sistem.

## Perubahan yang Diterapkan

### 1. Hapus Dead Code Sisa Era YOLO/Kamera (`StreamActivity.kt`)
- **`frameCollectJob`**: Variabel `Job?` ini dideklarasikan tetapi coroutine yang di-_launch_ berisi _body_ kosong — tidak melakukan apa-apa. Fungsi `startCollectingFrames()` dan pemanggilannya ikut dihapus.
- **`isInferencing`**: `AtomicBoolean(false)` yang tidak pernah di-set `true` dan tidak pernah dibaca. Sisa dari pipeline inferensi YOLO.
- **`bufferIndex`**: `Int = 0` yang tidak pernah dibaca maupun dimodifikasi.

### 2. Konsolidasi Deteksi Bluetooth (`StreamActivity.kt`)
Sebelumnya, `AudioManager.getDevices()` (panggilan lintas _system service_ yang cukup berat) dipanggil **dua kali setiap 200ms**: satu kali di _coroutine latency logger_ (`Dispatchers.Default`) dan satu kali di `updateLatencyMonitorUi()` (main thread).

Solusi: Tambahkan `@Volatile var hasBluetooth: Boolean` sebagai _cache_. Nilai ini di-_update_ sekali per 200ms di _coroutine latency logger_, dan fungsi `updateLatencyMonitorUi()` membaca dari _cache_ tersebut — tanpa query ulang ke sistem.

### 3. Perbaikan _Variable Shadowing_ (`TtsAlertManager.kt`)
Di dalam branch `dObj < T && alreadyAlerted`, terdapat `val lastSpoken` yang dideklarasikan di L247, kemudian dideklarasikan ulang (_shadowing_) dua kali lagi di L263 dan L272 dengan nilai yang identik. Kedua deklarasi dalam (_inner_) dihapus, menggunakan variabel luar yang sudah ada.

### 4. Dokumentasi Trade-off Satuan (`TtsAlertManager.kt`)
Operasi `vHeadBase * dObj` menggunakan `dObj` dalam satuan **mm** (bukan meter). Hasilnya menjadi sangat besar, tetapi dilindungi oleh _cap_ `if (T > 4000) T = 4000`. Ditambahkan komentar `// ponytail:` untuk menjelaskan trade-off ini kepada pembaca di masa mendatang.

## Status
✅ Diimplementasikan dan lulus kompilasi (_Build Successful_).
