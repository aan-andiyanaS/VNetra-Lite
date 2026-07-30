# Fix Bug dan Optimalisasi Hasil Audit Sistem VNetra-Lite

**Deskripsi**
Issue ini mencakup perbaikan berdasarkan hasil audit sistem menyeluruh dengan 5-axis review (correctness, readability, architecture, security, performance).

**Perbaikan yang diterapkan:**
1. **[C10] Defensive Unsigned Cast**: Memperbaiki potensi bug parsing nilai ToF dengan menambahkan mask `and 0xFFFF` agar `Short` tidak diinterpretasikan sebagai nilai negatif (StreamService).
2. **[C9] Komentar Trade-off vAvg**: Menambahkan dokumentasi di dalam kode mengenai fallback ke pembacaan `vRaw` terbaru (double-weighting) untuk safety threshold yang lebih tinggi (NavigationCoordinator).
3. **[CLAIM 2] Responsivitas Pendekatan Cepat**: Mengubah `EMA_ALPHA` dari `0.3f` menjadi `0.6f` agar sistem ToF lebih responsif terhadap objek yang mendekat dengan cepat, meminimalisir delay/lag ~500ms (SpatialMappingUtils).
4. **[A3] Testability SpatialMappingUtils**: Menambahkan fungsi `@VisibleForTesting fun reset()` untuk menghapus state mutable singleton saat unit test, mencegah kebocoran state antar test (SpatialMappingUtils).
5. **[R4/P5] Konstanta Latency**: Mengekstraksi *magic numbers* latensi (HW, ALGO, TTS) menjadi konstanta `LATENCY_HW_PING`, `LATENCY_ALGO_PING`, `LATENCY_TTS_PING` untuk dokumentasi yang lebih eksplisit bahwa latensi TTS belum diukur dinamis (StreamService).

**Status**
- Kode telah di-refactor menggunakan panduan `clean-code.md` dan metode *ponytail*.
- Commit akan dilakukan ke branch `coba`.
