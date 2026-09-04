# Implementation Plan: Dynamic Tanh Threshold

## 1. Goal
Mengganti algoritma `adaptiveThresholdMm` lama yang menggunakan penjepitan linear `min(4000, ...)` dengan formula *Single Sigmoid* ($\tanh$) yang disarankan oleh penguji. Formula ini memberikan kurva respon batas alarm yang asimtotik, organik, dan tahan terhadap *hard clipping*.

## 2. Hasil Audit Doubt-Driven Development
Sesuai perintah Anda `/doubt-driven-development` dan `/code-review-and-quality`, saya mencoba "menyerang" rumus ini untuk mencari potensi *bug* sebelum dikoding. Dan luar biasa, **SAYA MENEMUKAN SATU CELAH KRITIS!**

> [!CAUTION]
> **BUG KRITIS: Nilai T Menyusut di Bawah Titik Aman ($d_0$)**
> **Skenario:** Pengguna sedang berjalan lambat ($v \approx 100$ mm/s), namun tiba-tiba ditabrak oleh benda/orang dari depan. Tabrakan ini menghasilkan hentakan deselerasi (nilai $|a|$) yang sangat raksasa secara tiba-tiba (misal $|a| = 3000$ mm/s²).
> **Apa yang terjadi pada rumus?**
> $$ SSD = (100 \cdot 1.5) - (0.5 \cdot 3000 \cdot 0.5^2) $$
> $$ SSD = 150 - 375 = \mathbf{-225} \text{ mm} $$
> **Dampak:** Jarak $SSD$ menjadi **minus**. Akibatnya, nilai $\tanh(-x)$ akan menghasilkan angka minus. Batas alarm $T$ yang seharusnya dijaga minimal 1000 mm ($d_0$), justru ikut terseret susut menjadi misalnya 800 mm! Ini adalah pelanggaran keselamatan absolut.

> [!TIP]
> **Solusi:**
> Kita wajib menambahkan fungsi `max(0f, SSD)` (Penjepit Lantai) agar hasil perhitungan pengereman tidak pernah membalikkan jarak menjadi angka negatif, sekeras apa pun hentakan deselerasi terjadi secara fisik.

## 3. Proposed Changes

### [MODIFY] [NavigationCoordinator.kt](file:///e:/Project/Skripsi/VNetra-Lite/app/src/main/java/com/airi/vnetra/util/NavigationCoordinator.kt)

**Lokasi:** Baris 220 - 226

**Kode Lama:**
```kotlin
val linearAccelMmps2 = imuData[5] * 1000f
// Jarak Lunge = 0.5 * a * t_step^2
val momentumBufferMm = 0.5f * linearAccelMmps2 * (VNetraConfig.STEP_DURATION_SEC * VNetraConfig.STEP_DURATION_SEC)

adaptiveThresholdMm = (baseWarningDistanceMm + (emaApproachVelocityMmps * perceptionReactionTimeSec) + momentumBufferMm).toInt()
if (adaptiveThresholdMm > VNetraConfig.MAX_THRESHOLD_MM) adaptiveThresholdMm = VNetraConfig.MAX_THRESHOLD_MM
```

**Kode Baru (Revisi Penguji + Anti-Bug):**
```kotlin
val linearAccelMmps2 = imuData[5] * 1000f
val tStep = VNetraConfig.STEP_DURATION_SEC

// 1. Hitung Jarak Berhenti Total (SSD)
// Termasuk jarak tempuh selama t_step dikurangi deselerasi (momentum mengerem)
val rawSSD = (emaApproachVelocityMmps * perceptionReactionTimeSec) + 
             (emaApproachVelocityMmps * tStep) - 
             (0.5f * linearAccelMmps2 * tStep * tStep)

// FIX Anti-Bug: Cegah nilai deselerasi raksasa membuat SSD minus
val ssd = kotlin.math.max(0f, rawSSD)

// 2. Hitung Adaptive Threshold dengan tanh
val d0 = baseWarningDistanceMm.toFloat() // Titik terendah (1000mm)
val tMax = VNetraConfig.MAX_THRESHOLD_MM.toFloat() // Titik mentok (4000mm)

// Cek aman pembagian (mencegah infinity/NaN)
val maxSpan = kotlin.math.max(1f, tMax - d0)
val xRatio = ssd / maxSpan
val tanhMultiplier = kotlin.math.tanh(xRatio.toDouble()).toFloat()

adaptiveThresholdMm = (d0 + (maxSpan * tanhMultiplier)).toInt()
```

## 4. Verification Plan
- **Automated / Math Check:** Menggunakan nilai ekstrem (Kecepatan = 0, Kecepatan = Sangat Tinggi, Akselerasi = Minus Ekstrem) untuk memastikan $T$ tidak pernah kurang dari `1000` dan tidak pernah tembus melampaui `4000`.
- **Manual Verification:** Saat *run* di aplikasi Android, berjalan santai lalu berhenti mendadak (menciptakan akselerasi rem tinggi) dan pastikan alarm tidak tertunda secara aneh.

---
## User Review Required
Apakah Anda setuju dengan tambahan fungsi pelindung **`max(0f, rawSSD)`** di dalam kodenya? Jika Anda menyetujui *Implementation Plan* ini, tekan tombol **Proceed / Lanjutkan** dan saya akan langsung memodifikasi kodenya!

---

Viewed diagarm.md:56-74

Betul sekali! Nah, mari kita masukkan angka ke dalam **formula persis seperti yang Anda tuliskan itu** untuk melihat bagaimana *bug* maut ini bisa terjadi.

Mari kita gunakan Skenario Tunanetra Berjalan Sangat Pelan tapi Terbentur Keras:
*   $v_{avg} = 100$ mm/s (jalan santai)
*   $t_R = 1.0$ detik (waktu reaksi)
*   $t_{step} = 0.5$ detik (durasi langkah)
*   $|a_{lin}| = 3000$ mm/s² (hentakan deselerasi mendadak karena ditabrak orang)
*   $T_{max} = 4000$ mm
*   $d_0 = 1000$ mm

Mari kita hitung menggunakan 3 langkah formula Anda:

### Langkah 1: Jarak Berhenti Total ($SSD$)
$$ SSD = 100 \cdot 1.0 + \left( 100 \cdot 0.5 - \frac{1}{2} \cdot 3000 \cdot (0.5)^2 \right) $$
$$ SSD = 100 + \left( 50 - 1500 \cdot 0.25 \right) $$
$$ SSD = 100 + (50 - 375) $$
$$ SSD = 100 - 325 $$
**$$ SSD = -225 \text{ mm} $$** *(Angkanya jadi minus!)*

### Langkah 2: Normalisasi ($x$)
$$ x = \frac{-225}{4000 - 1000} $$
$$ x = \frac{-225}{3000} $$
**$$ x = -0.075 $$**

### Langkah 3: Kalkulasi Ambang Batas Akhir ($T$)
(Nilai $\tanh$ dari -0.075 adalah sekitar -0.0748)
$$ T = 1000 + 3000 \cdot \tanh(-0.075) $$
$$ T = 1000 + 3000 \cdot (-0.0748) $$
$$ T = 1000 - 224.4 $$
**$$ T = 775.6 \text{ mm} $$**

---
**KESIMPULAN: LIHAT APA YANG TERJADI PADA NILAI $T$**
Nilai $T$ (Batas Alarm) yang tadinya Anda lindungi mati-matian agar **minimal selalu 1000 mm ($d_0$)**, justru jeblok tembus ke bawah menjadi **775 mm**. 
Ini sangat berbahaya karena sistem baru akan memperingatkan tunanetra ketika benda sudah berjarak 775 mm (terlalu dekat!).

**Solusinya?**
Hanya perlu 1 baris kode tambahan di antara Langkah 1 dan Langkah 2:
Jika $SSD$ hasilnya minus, paksa angkanya jadi 0. (Di matematika ditulis: `SSD = max(0, SSD)`).

Dengan begitu:
$SSD$ = 0
$x$ = 0
$T$ = 1000 + 3000 * $\tanh(0)$ = 1000 mm.
Aman! Batas alarm tidak akan pernah bisa tembus ke bawah 1000 mm ($d_0$).

Jadi formula revisi dari penguji itu **sudah benar dan sangat bagus secara teori fisika**, hanya saja kurang 1 proteksi matematika di kodingannya. Apakah hitungan matematis di atas masuk akal?

---

Edited bug.md
Viewed bug.md:76-96

Betul, $100$ mm/s (0.1 meter per detik) itu memang sangat lambat (kondisi saat tunanetra sedang meraba-raba atau melangkah pelan-pelan karena ragu). 

Tapi, mari kita ubah ke kecepatan jalan normal manusia, yaitu **$500$ mm/s** (0.5 meter per detik). Apakah *bug* ini masih bisa terjadi? **Bisa!**

Bayangkan pengguna berjalan normal ($v_{avg} = 500$ mm/s), namun tiba-tiba tongkatnya membentur trotoar dengan keras, atau bahunya tertabrak orang berlari. Hentakan ke dada (tempat alat VNetra berada) bisa memicu akselerasi kejut (*shock acceleration*) sebesar **$1\text{ G}$** (Gravitasi).
Nilai $1\text{ G}$ itu setara dengan **$9800$ mm/s²**.

Mari kita hitung dengan kecepatan normal ($500$ mm/s) dan hentakan ($9800$ mm/s²):

### Langkah 1: Jarak Berhenti Total ($SSD$)
$$ SSD = 500 \cdot 1.0 + \left( 500 \cdot 0.5 - \frac{1}{2} \cdot 9800 \cdot (0.5)^2 \right) $$
$$ SSD = 500 + \left( 250 - 4900 \cdot 0.25 \right) $$
$$ SSD = 500 + (250 - 1225) $$
$$ SSD = 500 - 975 $$
**$$ SSD = -475 \text{ mm} $$**

*(Boom! Angkanya tetap minus, bahkan lebih parah minusnya!)*

### Langkah 2 & 3:
$$ x = \frac{-475}{3000} = -0.158 $$
$$ T = 1000 + 3000 \cdot \tanh(-0.158) $$
$$ T = 1000 + 3000 \cdot (-0.156) $$
$$ T = 1000 - 468 $$
**$$ T = 532 \text{ mm} $$**

Lihat? Sekalipun kecepatannya normal (500 mm/s), hanya karena IMU mendeteksi getaran/hentakan keras, nilai ambang batas $T$ langsung hancur menjadi **532 mm** (jauh di bawah batas aman 1000 mm). 

Di dunia nyata, sensor akselerometer (IMU) sangat sensitif terhadap getaran. Hentakan kaki yang keras saja bisa memicu lonjakan akselerasi sesaat. Jika tidak dipasangi `max(0, SSD)`, setiap kali pengguna menghentakkan kaki dengan keras, batas alarm mereka akan mengecil sesaat, dan itu bisa sangat fatal jika kebetulan ada rintangan di depan mereka.

Inilah alasan matematis mengapa kita harus selalu memasang "sabuk pengaman" `max(0, ...)` di dalam kode saat menggunakan formula pengurangan. 

Bagaimana? Apakah masuk akal kenapa pengaman ini bersifat wajib?

---

Edited bug.md
Viewed bug.md:127-138

Ah, angka **9800** memang terdengar sangat raksasa dan tidak masuk akal, tapi mari kita ingat bahwa satuan yang dipakai di kode Anda adalah **Milimeter (mm)**, bukan Meter (m).

Mari kita konversi kembali ke satuan asal (Meter) agar lebih terbayang:

1. Di alat Anda (ESP32/IMU), nilai akselerasinya memang masuk akal, misal **$9.8 \text{ m/s}^2$**. (Angka 9.8 m/s² ini adalah setara dengan 1 G, alias getaran/hentakan standar saat kita tersandung, melompat kecil, atau membentur trotoar).
2. Saat masuk ke HP Android (kode Kotlin), Anda mengalikannya dengan 1000:
   `val linearAccelMmps2 = imuData[5] * 1000f`
3. Maka: **$9.8 \text{ m/s}^2 \times 1000 = 9800 \text{ mm/s}^2$**.

Jadi angka **9800 mm/s²** itu sebenarnya hanyalah **9.8 m/s²**. Angka ini SANGAT MUNGKIN dan SANGAT WAJAR terjadi di dunia nyata setiap kali sensor bergetar keras atau dada pengguna terhentak.

Lalu, bagaimana efek hentakan wajar (9.8 m/s²) ini pada jarak pengereman?
Coba bayangkan Anda mengerem dengan kekuatan 9.8 m/s² selama 0.5 detik.
Jarak pengeremannya = $\frac{1}{2} \cdot 9800 \cdot (0.5)^2$ = **1225 mm** (atau 1.2 meter).

Nah, karena di rumus baru pengereman ini bersifat **mengurangi** (minus), maka jarak 1.2 meter ini akan dipotongkan dari kecepatan jalan Anda. Karena kecepatan jalan Anda hanya menghasilkan angka positif kecil (misal 750 mm), dipotong pengurang yang besar (1225 mm), hasilnya pun jebol jadi **minus**.

Apakah masuk akal bahwa angka "9800" itu sebenarnya hanyalah "9.8" yang dikalikan 1000?

---

Edited bug.md
Viewed bug.md:132-151

Pertanyaan yang sangat cerdas! Ini adalah salah satu kesalahpahaman paling umum dalam ilmu fisika (tertukar antara **Kecepatan** dan **Akselerasi**).

Secara logika awam: *"Kalau jalannya pelan, pasti akselerasinya juga kecil dong?"*
Faktanya di dunia fisika: **TIDAK ADA HUBUNGANNYA!**

Mari kita pisahkan:
1. **Kecepatan ($v_{avg}$):** Seberapa pelan/cepat Anda melangkah maju. (Misal: 100 mm/s).
2. **Akselerasi ($a_{lin}$):** Seberapa **kaget, tiba-tiba, atau terhentak** pergerakan Anda.

### Ilustrasi "Jalan Pelan tapi Akselerasi Raksasa":
Bayangkan Anda sedang berjalan **sangat, sangat pelan** (kecepatan kura-kura: 100 mm/s) di dalam rumah karena mati lampu.
Tiba-tiba, dahi Anda **membentur ujung pintu kayu**. Buk!
Dalam waktu **0.01 detik**, tubuh Anda yang tadinya maju 100 mm/s langsung terhenti mati menjadi 0 mm/s.

Mari kita hitung akselerasi (Gaya Hentakan) dari benturan itu:
$$ a = \frac{\text{Perubahan Kecepatan}}{\text{Waktu Benturan}} $$
$$ a = \frac{100 \text{ mm/s}}{0.01 \text{ detik}} = \mathbf{10.000 \text{ mm/s}^2} $$

Lihat? Walaupun Anda berjalan sangat lambat, tapi karena Anda *terhenti secara mendadak* (waktunya sangat singkat), IMU di dada Anda akan merasakan hentakan sebesar **10.000 mm/s²** (setara 1 G lebih)!

### Contoh Lain di Keseharian Tunanetra:
*   Berjalan pelan, lalu tongkatnya tersangkut lubang trotoar dan tubuhnya terjerembab ke depan.
*   Berjalan pelan, lalu bahunya ditabrak dari samping oleh anak kecil yang sedang lari.
*   Berjalan pelan, lalu tumit kakinya menghentak lantai keramik terlalu keras saat melangkah.

Semua kejadian "tiba-tiba" di atas menghasilkan **Akselerasi (hentakan) yang raksasa**, tidak peduli selambat apa pengguna berjalan. 

Sensor Accelerometer (IMU) itu sangat sensitif terhadap **getaran dan hentakan**. Itulah mengapa angka $a_{lin}$ bisa melompat liar ke angka ribuan mm/s² hanya karena pengguna tersandung kecil saat berjalan pelan. Dan karena itulah, kita **wajib** memasang pengaman `max(0, SSD)` agar hentakan sekecil apapun tidak merusak batas alarm keamanan tunanetra.

Apakah ilustrasi menabrak pintu ini bisa memperjelas perbedaan antara kecepatan lambat vs akselerasi raksasa?

---

Ini adalah pertanyaan yang sering ditanyakan oleh orang yang belajar fungsi Sigmoid! Kenapa tidak pakai rumus Sigmoid Logistik biasa $S(x) = \frac{1}{1 + e^{-x}}$?

Jawabannya murni karena **Titik Awal (Angka Nol)** saat pengguna sedang diam berdiri. 

Mari kita buktikan dengan matematika sederhana. Bayangkan Anda sedang **berdiri diam total**.
Kecepatan = 0, Akselerasi = 0, sehingga Jarak Berhenti ($SSD$) = 0. Karena $SSD = 0$, maka $x = 0$.

Mari kita masukkan angka $x = 0$ ini ke kedua rumus:

### 1. Jika Pakai $\tanh(x)$ (Rumus Pilihan Penguji)
*   $\tanh(0) = \mathbf{0}$
*   $T = 1000 + 3000 \cdot \tanh(0)$
*   $T = 1000 + (3000 \cdot 0)$
*   **$T = 1000 \text{ mm}$ (Benar!)**
Saat Anda berdiri diam, batas alarm mengecil sempurna kembali ke batas dasar ($d_0$) yaitu 1 meter. Ini sangat akurat dan nyaman bagi pengguna.

### 2. Jika Pakai Sigmoid Biasa $S(x)$
*   $S(0) = \frac{1}{1 + e^0} = \frac{1}{1 + 1} = \frac{1}{2} = \mathbf{0.5}$
*   $T = 1000 + 3000 \cdot S(0)$
*   $T = 1000 + (3000 \cdot 0.5)$
*   $T = 1000 + 1500$
*   **$T = 2500 \text{ mm}$ (SALAH BESAR!)**
Bayangkan! Anda sedang berdiri diam santai, tapi batas alarm Anda malah membengkak jadi 2.5 meter. Akibatnya, setiap kali Anda berdiri di dekat tembok/orang sejauh 2 meter, alat akan bergetar kencang (padahal Anda tidak sedang bergerak maju). Alat ini akan menjadi sangat cerewet dan menjengkelkan (Spam).

---

**Kesimpulan:**
Fungsi Sigmoid Biasa $S(x)$ itu kurvanya dimulai dari tengah-tengah (angka 0.5 pada titik X=0). Sedangkan $\tanh(x)$ kurvanya membelah tepat di titik tengah (angka 0 pada titik X=0).

Karena kita butuh nilai penambahan pengeremannya **NOL** saat pengguna sedang diam, maka $\tanh(x)$ adalah satu-satunya kurva Sigmoid yang secara matematis cocok untuk aplikasi kecepatan fisika ini.

Bagaimana? Sangat logis kan kenapa penguji memaksa menggunakan $\tanh$?

---

Edited bug.md
Edited ide%20system.md
Viewed diagarm.md:49-69

Menjawab pertanyaan pertama: **Iya, 100% benar-benar tercapai!** 
Dengan tambahan `max(0, SSD)`, maka saat Anda berhenti/berdiri diam, hasil akhirnya akan **persis** 1000 mm. Tidak meleset 1 milimeter pun.

---

Menjawab ide rumus alternatif Anda:
$$ T = T_{max} \cdot \tanh\!\left(\frac{d_0 + SSD}{T_{max}}\right) $$

Itu adalah ide yang sangat kreatif untuk mencoba memasukkan $d_0$ ke dalam kurung $\tanh$! 
Tapi, sayangnya jika Anda menggunakan rumus ini, **rumusnya akan rusak secara matematis**.

Mari kita buktikan kenapa memasukkan $d_0$ ke dalam kurung $\tanh$ itu dilarang keras di matematika.

**Skenario Berdiri Diam:**
$v = 0, a = 0 \rightarrow SSD = 0$.
$d_0 = 1000$
$T_{max} = 4000$

Mari hitung menggunakan ide rumus Anda:
$$ T = 4000 \cdot \tanh\!\left(\frac{1000 + 0}{4000}\right) $$
$$ T = 4000 \cdot \tanh(0.25) $$
*(Nilai $\tanh$ dari 0.25 adalah 0.2449)*
$$ T = 4000 \cdot 0.2449 $$
**$$ T = 979.6 \text{ mm} $$**

**BOOM! Terlihat masalahnya?**
Saat tunanetra sedang berdiri diam total, batas alarmnya malah menyusut menjadi **979.6 mm**, tidak bisa mencapai target 1000 mm yang diwajibkan! 

### Kenapa Bisa Begitu?
Sifat asli dari kurva $\tanh(x)$ adalah ia selalu **"memampatkan" (menekan)** nilai yang ada di dalam kurungnya. 
Jika Anda memasukkan $d_0$ (angka 1000) ke dalam kurung $\tanh$, maka angka 1000 itu akan ikut "tergencet" oleh kurva $\tanh$ menjadi angka yang lebih kecil.

### Kenapa Rumus Penguji Jauh Lebih Sempurna?
$$ T = d_0 + (T_{max} - d_0) \cdot \tanh(x) $$
Lihat posisi $d_0$ di rumus penguji?
Angka $d_0$ (1000 mm) diletakkan di **LUAR** jangkauan $\tanh$. 
Ini bertindak sebagai **Pondasi Beton yang Tidak Bisa Diganggu Gugat**. Fungsi $\tanh$ hanya bertugas untuk mengurus *seberapa panjang karetnya ditarik*, tanpa pernah mengganggu ukuran balok pondasi 1000 mm di bawahnya.

Secara matematis, rumus revisi dari penguji (yang dimodifikasi dengan pengaman `max(0)`) adalah **Sempurna Tiada Tara**.

Apakah penjelasan tentang bahayanya "memasukkan $d_0$ ke dalam kurung $\tanh$" ini bisa dipahami?

---

Betul sekali, Anda memang sudah memasang filter **EWMA (Exponential Moving Average)** di `firmware-vnetra.ino` (Baris 834):
`a_lin_smooth = (ema_alpha * a_lin_mag_raw) + ((1.0f - ema_alpha) * a_lin_smooth);`

Tapi mari kita bedah secara matematis **kelemahan natural dari EWMA** saat menghadapi hentakan fisik nyata (bukan sekedar getaran listrik).

Misalkan Anda tersandung. Proses tubuh Anda terhentak dan mengerem mendadak itu tidak terjadi dalam 1 milidetik, melainkan berlangsung sekitar **0.15 detik (3 Frame di IMU)**. Gaya hentakannya misal mentok di batas maksimal sensor yaitu $20 \text{ m/s}^2$ ($20.000 \text{ mm/s}^2$).

Mari kita simulasikan EWMA Anda bekerja (dengan `alpha = 0.4` saat bergerak):
*   **Frame 1:** `smooth` = $(0.4 \cdot 20.000) + (0.6 \cdot 0)$ = **$8.000 \text{ mm/s}^2$**
*   **Frame 2:** `smooth` = $(0.4 \cdot 20.000) + (0.6 \cdot 8000)$ = **$12.800 \text{ mm/s}^2$**
*   **Frame 3:** `smooth` = $(0.4 \cdot 20.000) + (0.6 \cdot 12800)$ = **$15.680 \text{ mm/s}^2$**

Lihat hasilnya? 
Hanya dalam waktu sekejap mata (3 frame), filter EWMA Anda **TETAP JEBOL** hingga menyentuh angka **$15.680 \text{ mm/s}^2$**.

EWMA itu sangat hebat untuk memuluskan getaran frekuensi sangat tinggi (seperti tangan yang bergetar atau sinyal listrik yang kotor). Tapi EWMA **tidak akan sanggup (dan memang tidak boleh didesain untuk) menahan benturan fisik nyata yang agak panjang**, karena kalau nilai *alpha* EWMA dibuat terlalu kecil (misal 0.01) agar angkanya tidak naik, sensor Anda akan jadi **sangat lemot (lagging)** dan alarm akan telat merespon bahaya!

Karena itulah, setelan EWMA Anda saat ini (*alpha* 0.4) sudah sangat pas agar responsif. Namun efek samping dari responsif adalah: angka $a_{lin}$ tetap bisa melonjak sesaat dan mengancam membuat $SSD$ menjadi minus. 

Disitulah `max(0, SSD)` masuk sebagai benteng pertahanan terakhir. Logis kan?