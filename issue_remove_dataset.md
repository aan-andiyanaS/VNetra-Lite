# Issue: Penghapusan Fitur Pengumpulan Dataset dan Sisa Logika YOLO

## Deskripsi
Sistem VNetra-Lite pada iterasi sebelumnya masih memuat modul *DatasetManager* dan sisa logika yang dipersiapkan untuk inferensi AI berbasis kamera (YOLO). Pendekatan ini terbukti tidak efisien untuk target skripsi saat ini yang hanya berfokus pada analisis *terrain* / ruang spasial murni menggunakan matriks Time-of-Flight (ToF) 8x8 dan IMU.

Fitur *logging* gambar dan mode pengumpulan dataset menyebabkan *overhead* memori dan redundansi antarmuka, serta berisiko memperlambat latensi *end-to-end* yang sangat kritis untuk navigasi *real-time*.

## Solusi yang Diterapkan
Pembersihan (*Clean Code* & *Doubt-Driven*) dilakukan dengan menerapkan prinsip *"YAGNI" (You Aren't Gonna Need It)*:
- **Menghapus `DatasetManager.kt`**: Modul perekaman *frame* gambar dihapus sepenuhnya.
- **Pembersihan `StreamActivity.kt`**: Menghapus inisialisasi, variabel, dan *listener* yang berkaitan dengan mode dataset.
- **Pembersihan UI**: Menghapus `ToggleButton` (Mode Dataset) dari `activity_stream.xml`.
- **Fokus Tunggal TTS**: Memastikan `TtsAlertManager` sekarang hanya menerima *input* dari `SpatialMappingUtils.analyzeTerrain()`, tanpa adanya label objek visual (kursi, orang, dll). Sistem hanya akan melaporkan "tembok arah X" atau "halangan arah Y".

## Status
Telah Diimplementasikan dan Lulus Kompilasi (*Build Successful*).
