# VNetra-Lite Architecture & Code Quality Audit

**Tanggal Audit:** Agustus 2026
**Standar Evaluasi:** `Clean Code`, `Doubt-Driven Development`, `Debugging & Error Recovery`, dan `Ponytail Full` (Pragmatis & Minimalis).

---

## 1. 🚨 FATAL ARCHITECTURE FLAW (Doubt-Driven & Ponytail)
**Tingkat Keparahan: Kritis (Berpotensi mencelakakan pengguna tunanetra)**

Berdasarkan teori yang Anda susun secara cemerlang di `EWMA.md` dan `BAB II 2.8.md`, filter pemulusan jarak dan perhitungan **Time-To-Collision (TTC)** adalah jantung keselamatan sistem Anda. 

Namun, hasil inspeksi pada *codebase* menunjukkan bahwa logika krusial ini **diimplementasikan di aplikasi Android (`NavigationCoordinator.kt`)**, bukan di *Firmware* ESP32!
*   **Baris 201:** `vRawEma = (0.4f * vRaw) + (0.6f * vRawEma)`
*   **Baris 214:** `T = (d_W0 + (vAvg * tR) + momentumBuffer).toInt()`

### Skenario Terburuk (Doubt-Driven):
Sistem Anda saat ini bekerja dengan mengirimkan aliran data (ToF + IMU) via jaringan nirkabel (Wi-Fi/UDP) ke HP. Jaringan nirkabel jalanan sangat rentan terhadap *packet loss* (kehilangan data) atau *delay*.
Jika satu saja paket UDP hilang, variabel waktu (`dt`) di Android akan kacau. Turunan matematika $\frac{\Delta d}{\Delta t}$ akan meledak karena `dt` tidak konsisten. Akibatnya, filter EWMA di Android akan berhalusinasi mendeteksi kecepatan yang tak nyata, dan sistem gagal memperingatkan rintangan yang sesungguhnya.

### Solusi Wajib (Prinsip Edge Computing):
**Pindahkan seluruh beban matematika (EWMA, Kinematika Hukum Newton, dan TTC) ke dalam C++ di `firmware-vnetra.ino`!**
ESP32S3 adalah mikrokontroler *Dual-Core* berkecepatan 240MHz. Membebankan hitungan pecahan (*float*) ini ke ESP32 sama sekali tidak akan membuatnya berkeringat.
Aplikasi Android Anda (Kotlin) seharusnya di-*downgrade* menjadi sebuah **"Dumb Speaker"** yang sekadar menerima sinyal status sederhana dari ESP32 (misalnya: `[STATUS: DANGER, JARAK: 80cm]`) lalu membacakannya via *Text-To-Speech* (TTS).

---

## 2. 🧹 KONSISTENSI CLEAN CODE (Penamaan Variabel & Fungsi)
**Tingkat Keparahan: Menengah**

Anda mencampur-adukkan penamaan singkatan akademis (*Academic Math*) dengan standar baku bahasa pemrograman *Kotlin* (*CamelCase*). Ini membuat kode sangat sulit dirawat (*maintainable*) oleh programmer lain.

**Temuan di `NavigationCoordinator.kt`:**
*   **Bagus:** `vRaw`, `momentumBuffer`, `dt` (standar CamelCase).
*   **Buruk:** `a_lin_mm_s2`, `d_W0`, `tR`. Ini adalah *Snake_case* yang melanggar standar konvensi penamaan Java/Kotlin, serta menggunakan singkatan akademis yang kriptik.

**Solusi Clean Code:**
Kode harus dibaca oleh manusia, bukan dosen matematika. Jangan takut dengan nama variabel yang panjang asalkan deskriptif.
*   Ubah `a_lin_mm_s2` menjadi `linearAccelMmPerSecSq`
*   Ubah `d_W0` menjadi `safeDistanceThreshold`
*   Ubah `tR` menjadi `reactionTime`

---

## 3. 🛡️ DEBUGGING & ERROR RECOVERY
**Tingkat Keparahan: Tinggi**

Sebuah alat navigasi keselamatan (*Electronic Travel Aid*) wajib memiliki mekanisme pertahanan dari kegagalan fungsi (*Failsafe*).

**Temuan:**
1.  **Sapu di Bawah Karpet:** Di `NavigationCoordinator.kt` baris 219 terdapat kode: `val filteredYawRate = if (abs(yawRate) < 4.0f) 0f else yawRate`. Alih-alih mengimplementasikan filter rekursif pelacak orientasi yang benar (seperti *Mahony* atau *Madgwick* filter) secara utuh, Anda hanya memangkas (*threshold*) *noise* kecil. Ini rentan menghasilkan lompatan data saat *noise* sesekali melebihi 4.0f.
2.  **Kehilangan Koneksi:** Jika *smartphone* kehabisan baterai atau aplikasi Android tiba-tiba *crash* (*Force Close*), kacamata/tongkat ESP32 saat ini tidak memiliki cara untuk memberitahu penggunanya secara mandiri. Tunanetra akan terus berjalan mengira sistem masih aman.

**Solusi Error Recovery:**
1.  Pasang filter *Mahony* secara tuntas di dalam ESP32.
2.  Tambahkan komponen *Buzzer* atau *Vibration Motor* kecil langsung pada *board* ESP32. Jika aplikasi Android terputus lebih dari 2 detik (melalui deteksi hilangnya *Heartbeat Packet*), ESP32 harus membunyikan alarm *Buzzer* panjang secara perangkat keras untuk memperingatkan pengguna bahwa sistem navigasi utama mati.

---

## 4. ✂️ PRINSIP PONYTAIL (YAGNI - You Aren't Gonna Need It)
Berhentilah melakukan hal rumit (seperti mensinkronisasikan Timestamp Sensor vs Timestamp Android) jika Anda bisa menyederhanakannya!

Dengan memindahkan semua hitungan ke dalam mikrokontroler (ESP32):
1. Anda memangkas *bandwidth* data dari yang tadinya raksasa menjadi hanya beberapa *byte* per detik (hanya kirim sinyal peringatan bahaya).
2. Anda memangkas kode ratusan baris di Kotlin yang rentan *Bug* dan memindahkannya ke hitungan lokal C++ yang pasti konsisten.
3. Arsitektur sistem berubah dari **"HP Pintar, Tongkat Bodoh"** menjadi arsitektur tingkat dewa: **"Tongkat Super Pintar, HP Bodoh"**.

---

### Kesimpulan Akhir
Secara fondasi matematis (Skripsi Bab 2), sistem Anda adalah mahakarya. Namun secara Arsitektur Perangkat Lunak, sistem Anda saat ini adalah **bom waktu** (*Time Bomb*). Jangan biarkan teori yang luar biasa bagus ini hancur karena arsitektur jaringan yang keliru.

Jika Anda menyetujui hasil audit ini, tahap selanjutnya adalah merefaktor (membedah dan menyusun ulang) kode dari Android ke ESP32.
