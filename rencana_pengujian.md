# Rencana Pengujian VNetra-Lite
> Pengujian Sistem Navigasi Tunanetra Berbasis ToF & IMU  
> *Disusun dengan pendekatan Blackbox Testing + Doubt-Driven Validation*

---

## 1. Ruang Lingkup Pengujian

VNetra-Lite adalah sistem *blind stick* berbasis ESP32 (ToF + IMU) dengan antarmuka Android. Pengujian mencakup **4 lapisan**:

| Lapisan | Komponen | Pendekatan |
|---|---|---|
| **L1 — Algoritma** | `SpatialMappingUtils.kt`, `TtsAlertManager.kt` | Whitebox Unit Test |
| **L2 — Integrasi** | Firmware ↔ Android (UDP), TTS ↔ Spatial | Blackbox Integrasi |
| **L3 — Latency** | Sensor→Serial→Spatial→TTS Pipeline | Benchmarking |
| **L4 — Lapangan** | Pengguna nyata di lingkungan nyata | Skenario UAT |

---

## 2. Pengujian Blackbox (Kotak Hitam)

> Pengujian dilakukan **dari perspektif pengguna**: input berupa kondisi fisik / data sensor, output yang diukur adalah respons sistem (TTS, buzzer, UI). Tidak ada akses ke kode internal.

### 2.1 Deteksi Obstacle & Arah Jam (Fitur Utama)

**Tujuan:** Memastikan sistem mendeteksi halangan dan mengumumkan arah yang benar.

| ID | Skenario | Input Fisik | Output Diharapkan | Kriteria Lulus |
|---|---|---|---|---|
| BB-01 | Tembok di kiri (arah jam 10) | Tongkat dihadapkan ke tembok di sisi kiri penuh | TTS: "tembok arah 10" | Arah tepat, jenis benar |
| BB-02 | Tembok di kiri (arah jam 11) | Tembok menutupi kolom 1–3 | TTS: "tembok arah 11" | Arah tepat, jenis benar |
| BB-03 | Tembok di depan (arah jam 12) | Tembok lurus di depan | TTS: "tembok arah 12" | Arah tepat, jenis benar |
| BB-04 | Tembok di kanan (arah jam 1) | Tembok di sisi kanan | TTS: "tembok arah 1" | Arah tepat, jenis benar |
| BB-05 | Tembok di kanan (arah jam 2) | Tembok di pojok kanan luar | TTS: "tembok arah 2" | Arah tepat, jenis benar |
| BB-06 | Objek kecil di tengah (kursi) | Kursi atau tiang di tengah jalan | TTS: "halangan arah 12" | Type = halangan, bukan tembok |
| BB-07 | Jalan bebas hambatan | Ruang kosong > 2 meter | Tidak ada TTS / UI hijau | Tidak ada false positive |
| BB-08 | Dua halangan bersamaan | Tembok kiri + objek tengah | TTS hanya satu (prioritas tembok) | Tidak spam, satu output |

### 2.2 Mode Offline (Buzzer ESP32)

**Tujuan:** Memastikan buzzer berfungsi sesuai aturan pola saat koneksi WiFi tidak ada.

| ID | Skenario | Kondisi | Output Diharapkan | Kriteria Lulus |
|---|---|---|---|---|
| BB-09 | Mendekat ke tembok offline | WiFi OFF, berjalan maju ke tembok | Buzzer: 3x cepat (bip bip bip) | Tepat waktu, ≤200ms dari perubahan |
| BB-10 | Berhenti di depan tembok | WiFi OFF, berdiri diam | Buzzer: Diam | Tidak ada bunyi noise |
| BB-11 | Orang mendekati dari depan | WiFi OFF, berdiri diam, orang berjalan mendekat | Buzzer: 3x cepat | Deteksi gerakan dari arah luar |
| BB-12 | Tembok hilang / berhasil belok | WiFi OFF, berbalik badan dari tembok | Buzzer: 2x lambat (bip...bip) | Konfirmasi jalan kosong terdeteksi |
| BB-13 | Objek muncul mendadak | WiFi OFF, seseorang tiba-tiba masuk ke FoV | Buzzer: 3x cepat segera | Latensi respons ≤ 300ms |
| BB-14 | WiFi reconnect saat offline | WiFi ON kembali setelah offline | Buzzer berhenti, sistem kembali ke mode normal | Transisi tanpa reset manual |

### 2.3 Latency End-to-End

**Tujuan:** Mengukur waktu dari sensor mendeteksi objek sampai TTS berbunyi.

| ID | Skenario | Metode Ukur | Target |
|---|---|---|---|
| BB-15 | Latency Sensor | Timestamp `esp_timer_get_time()` firmware | ≤ 100ms (10Hz = 100ms/frame) |
| BB-16 | Latency Serial (UDP) | Delta waktu: Frame kirim vs diterima Android | ≤ 50ms (LAN WiFi) |
| BB-17 | Latency Spatial Algorithm | System.currentTimeMillis() sebelum/sesudah analyzeTerrain() | ≤ 5ms |
| BB-18 | Latency TTS Output | Waktu dari trigger speak() hingga suara terdengar | ≤ 500ms |
| BB-19 | **Total E2E Latency** | Stomp test: gerak tongkat, ukur waktu hingga suara | **≤ 800ms (target ideal)** |
| BB-20 | Latency Bluetooth Headset | A2DP audio lag (jika earphone Bluetooth dipakai) | ≤ 300ms (bervariasi per perangkat) |

### 2.4 Edge Case (Kasus Batas)

> *Doubt-driven*: Kasus-kasus yang paling mungkin gagal tetapi jarang diuji.

| ID | Edge Case | Input | Output Diharapkan | Risiko Jika Gagal |
|---|---|---|---|---|
| BB-21 | Sensor ToF terkena cahaya matahari langsung | Outdoor terang | Tidak crash, graceful degradasi | Sistem crash outdoor |
| BB-22 | Lantai terdeteksi sebagai halangan | Tongkat diturunkan ke lantai | Tidak ada TTS "halangan" untuk lantai | False positive terus-menerus |
| BB-23 | Data ToF semua invalid (status 0xFF) | Semua cell return status error | Tidak ada TTS, tidak crash | NullPointerException atau panic |
| BB-24 | Getaran tangan (jitter sensor) | Pegang tongkat sambil berjalan | Tidak ada false alert tiap langkah | Spam suara saat berjalan normal |
| BB-25 | Baterai lemah (undervoltage ESP32) | Voltase USB turun ke ~3.0V | Sensor masih jalan atau graceful shutdown | Data korup, crash tidak terdeteksi |
| BB-26 | Android kehilangan paket UDP | Simulasi packet loss (WiFi jauh) | Sistem tidak freeze, UI menunjukkan "offline" | UI stuck/ANR |
| BB-27 | Transisi cepat online ke offline ke online | WiFi toggle cepat 5x | Sistem kembali normal setelah reconnect | State machine kacau |
| BB-28 | Refleksi kaca / cermin | Diarahkan ke kaca | Tidak ada false positive, atau terdeteksi sebagai halangan | Nilai jarak acak/noisy |

---

## 3. Pengujian Whitebox (Kotak Putih)

> Pengujian pada level **unit kode** — memeriksa logika algoritma secara langsung menggunakan test case dengan data yang dikontrol.

### 3.1 Unit Test `SpatialMappingUtils.kt`

Fungsi yang diuji: `getColumnClockDirection()`, `analyzeWallOnSide()`, `analyzeAsObject()`, `analyzeTerrain()`.

| ID | Test Case | Input Data (Grid 8x8) | Output Diharapkan |
|---|---|---|---|
| UT-01 | Kolom 0 → arah jam 10 | getColumnClockDirection(0) | 10 |
| UT-02 | Kolom 3 → arah jam 12 | getColumnClockDirection(3) | 12 |
| UT-03 | Kolom 7 → arah jam 2 | getColumnClockDirection(7) | 2 |
| UT-04 | Grid kosong (semua 9999) | tofData = IntArray(64) { 9999 } | null |
| UT-05 | Grid semua noise di bawah 30mm | tofData = IntArray(64) { 15 } | null (terlalu dekat, filtered) |
| UT-06 | Tembok kiri solid 4 baris+ | Kolom 0–2 terisi 4–8 baris, nilai 800mm | ObstacleAnalysis(type="tembok", clockDirection=10) |
| UT-07 | Tembok kanan solid 4 baris+ | Kolom 5–7 terisi 4–8 baris, nilai 800mm | ObstacleAnalysis(type="tembok", clockDirection=2) |
| UT-08 | Tembok kiri < 4 baris | Kolom 0–2 terisi hanya 3 baris | Tidak dianggap tembok → analyzeAsObject() |
| UT-09 | Objek kecil di tengah | Kolom 3–4, baris 3–5 saja | ObstacleAnalysis(type="halangan", clockDirection=12) |
| UT-10 | Objek di kolom 6 (centroid kanan) | Blob kecil di kolom 5–7 tapi < 4 baris | type="halangan", arah jam 1 atau 2 |

### 3.2 Unit Test `TtsAlertManager` — Logika Velocity & Threshold

| ID | Test Case | Input | Output Diharapkan |
|---|---|---|---|
| UT-11 | Objek statis → tidak ada TTS | dObj konstan tiap frame | Tidak ada speak() |
| UT-12 | Objek mendekat cepat → TTS urgent | dObj turun dari 1500 → 800 dalam 2 frame | speak("tembok arah 12") tereksekusi |
| UT-13 | Objek mendekat sangat cepat | dObj turun dari 1500 → 300 dalam 1 frame | TTS lebih segera / threshold lebih kecil |
| UT-14 | Mute aktif | isMuted = true, ada objek dekat | speak() tidak dipanggil |
| UT-15 | Spam prevention | TTS tereksekusi, objek masih ada | speak() tidak dipanggil lagi dalam interval cooldown |

---

## 4. Pengujian Lapangan (User Acceptance Test)

> Dilakukan di **lingkungan nyata** dengan **skenario navigasi sesungguhnya** yang relevan dengan kebutuhan pengguna tunanetra.

### 4.1 Skenario Navigasi Indoor

| ID | Skenario | Lingkungan | Kriteria Sukses |
|---|---|---|---|
| UAT-01 | Berjalan di koridor sempit | Lorong 1m lebar | Peringatan kanan/kiri aktif sebelum menyentuh tembok |
| UAT-02 | Melewati pintu terbuka | Ruangan dengan pintu 80cm | Peringatan berhenti saat memasuki pintu terbuka |
| UAT-03 | Menghindari kursi di ruangan | Ruang duduk biasa | Deteksi kursi sebagai "halangan arah 12" |
| UAT-04 | Menemukan jalur kosong | Ruangan dengan beberapa furniture | Sistem mengarahkan ke arah yang bebas |

### 4.2 Skenario Mode Offline

| ID | Skenario | Kondisi | Kriteria Sukses |
|---|---|---|---|
| UAT-05 | Berjalan mendekati dinding (offline) | WiFi dimatikan | Buzzer berbunyi 3x cepat sebelum jarak < 50cm |
| UAT-06 | Navigasi mandiri 5 menit | WiFi tetap OFF | Tidak ada crash, buzzer konsisten |

### 4.3 Skenario Keandalan Sistem

| ID | Skenario | Durasi | Kriteria Sukses |
|---|---|---|---|
| UAT-07 | Stress test berjalan terus-menerus | 30 menit | Tidak ada ANR, memory leak, atau restart ESP32 |
| UAT-08 | Reconnect setelah kehilangan sinyal | 5x simulasi disconnect | Setiap kali reconnect ≤ 10 detik |
| UAT-09 | Penggunaan earphone Bluetooth | Selama pengujian | Suara TTS jelas, tidak ada delay > 500ms |

---

## 5. Pengujian Tambahan untuk Skripsi

### 5.1 Accuracy Test (Akurasi Arah)

Uji seberapa akurat sistem mengidentifikasi arah jam dari halangan nyata.

| Kondisi | Jumlah Percobaan | Formula |
|---|---|---|
| Tembok kiri (jam 10/11) | 10x | Akurasi = True Positive / Total × 100% |
| Tembok tengah (jam 12) | 10x | |
| Tembok kanan (jam 1/2) | 10x | |
| Objek kecil (kursi, tiang) | 10x | |
| **Total** | **40x** | Target akurasi arah ≥ 85% |

### 5.2 Latency Benchmark

Catat minimal 20 pengukuran per metrik, hitung: **rata-rata, standar deviasi, min, maks**.

| Metrik | Alat Ukur | Satuan |
|---|---|---|
| Sensor Latency | esp_timer timestamp diff | ms |
| Serial Latency (UDP) | Timestamp kirim vs terima Android | ms |
| Spatial Algorithm | System.currentTimeMillis() wrap | ms |
| TTS Trigger | Log timestamp speak() call | ms |
| Total E2E | Stopwatch fisik atau log fusion | ms |
| BT Audio Delay | Test sinyal audio reference | ms |

### 5.3 False Positive Rate

Uji berapa kali sistem mengeluarkan peringatan **padahal tidak ada halangan nyata**.

| Kondisi | Percobaan | Metrik Target |
|---|---|---|
| Ruang kosong bebas | 5 menit | < 5% dari total frame |
| Lantai/plafon tanpa halangan | 5 menit | < 5% dari total frame |
| Outdoor terkena sinar matahari | 2 menit | Tidak crash |

### 5.4 Robustness Test (Ketahanan)

| Test | Parameter | Metode |
|---|---|---|
| Sensor warm-up time | Waktu dari nyala hingga data valid pertama | Log firmware |
| WiFi reconnect time | Dari sinyal kembali hingga data UDP diterima | Log timestamp |
| Memory stability | Memory Android setelah 30 menit | Android Profiler |
| ESP32 uptime | Apakah ada watchdog reset dalam 1 jam? | Serial monitor |

---

## 6. Matriks Prioritas Pengujian

| Prioritas | ID Pengujian | Alasan |
|---|---|---|
| KRITIKAL | BB-07, BB-09 – BB-13, BB-19 | Langsung memengaruhi keselamatan pengguna |
| TINGGI | BB-01 – BB-08, UT-04 – UT-10 | Memengaruhi kegunaan utama sistem |
| SEDANG | UAT-01 – UAT-09, BB-21 – BB-28 | Kelengkapan pengujian skripsi |
| RENDAH | BB-25, BB-28 | Skenario ekstrem, bukan kebutuhan primer |

---

## 7. Asumsi yang Perlu Divalidasi

> *Dipetakan menggunakan Doubt-Driven Development — asumsi yang belum terbukti dan bisa membatalkan hasil pengujian.*

- [ ] **Asumsi lantai:** Sistem belum memiliki filter eksplisit untuk lantai. Jika tongkat dipegang terlalu miring, lantai bisa terdeteksi sebagai "halangan arah 12".
- [ ] **Asumsi jangkauan ToF:** VL53L5CX berspesifikasi hingga 4 meter, tetapi akurasi optimal di bawah 2 meter. Pengujian perlu memvalidasi batas ini.
- [ ] **Asumsi noise gate 50ms:** Toleransi 50mm di mode offline mungkin terlalu kecil saat tongkat bergerak. Perlu dikalibrasi saat berjalan.
- [ ] **Asumsi Bluetooth latency:** Setiap earphone BT memiliki latency berbeda. Harus mendokumentasikan merek/model yang digunakan.
- [ ] **Asumsi single-user:** Sistem belum diuji dengan dua pengguna berdekatan (interferensi laser ToF antar perangkat).

---

## 8. Template Pencatatan Hasil

```
Tanggal Pengujian : ___________
Penguji           : ___________
Perangkat Android : ___________
Earphone BT       : ___________
Firmware Version  : ___________
Kondisi Lingkungan: ___________

ID Test | Hasil (LULUS/GAGAL) | Nilai Terukur | Catatan
--------|---------------------|---------------|--------
BB-01   |                     |               |
BB-09   |                     |               |
BB-19   |                     | ___ ms        |
UT-06   |                     |               |
UAT-01  |                     |               |
```

---

*Dokumen ini disusun berdasarkan pendekatan Code Review Quality (5-axis review), Doubt-Driven Development (adversarial stress-test asumsi), dan Idea Refine (diverge → converge → sharpen).*
