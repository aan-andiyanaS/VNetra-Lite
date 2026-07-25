# Issue: Implementasi Background Latency Logger untuk Pengujian Skripsi

## Deskripsi
Dalam pengujian lapangan untuk skripsi, metrik latensi *end-to-end* (E2E) perlu dicatat secara konsisten selama sesi berjalan untuk keperluan perhitungan statistik (rata-rata, min, maks, standar deviasi). Menampilkan nilai metrik secara *real-time* di UI tidak cukup untuk analisis data yang masif. 

Oleh karena itu, diperlukan sistem *logger* yang berjalan di latar belakang tanpa mengganggu atau membebani performa aplikasi utama, namun tetap dapat mengekspor data yang dibutuhkan.

## Solusi yang Diimplementasikan
- **Penambahan `LatencyLogger`**: Membuat kelas *inner* di `StreamActivity.kt` khusus untuk menangani *logging* latensi.
- **Ring-Buffer (1000 Sampel)**: Setiap *frame* data yang diproses, nilai latensinya (Sensor, Serial, Algoritma, TTS, Bluetooth, dan Total) akan direkam ke memori sementara (*buffer*).
- **Auto-Flush 5 Detik**: Menggunakan *coroutine timer* untuk mencetak ringkasan metrik secara berkala ke Logcat (dengan tag `LAT`) setiap 5 detik agar memori tidak bocor.
- **Final Flush**: Menambahkan *hook* ke metode `akhiriProses()` agar sistem mencetak sisa *buffer* latensi keseluruhan ketika pengguna menutup aplikasi.

## Cara Mengekstrak Data
Pada PC yang terhubung via kabel data/Wi-Fi Debugging:
```powershell
adb logcat -s LAT > latency_sesi.txt
```
Data ini kemudian dapat langsung dibuka dan diolah dengan Excel atau Python untuk laporan skripsi.

## File yang Diubah
- `app/src/main/java/com/airi/vnetra/ui/StreamActivity.kt`
