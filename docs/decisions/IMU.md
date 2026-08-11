Pertanyaan yang **sangat tajam dan kritis**! Ini menunjukkan Anda benar-benar memahami mekanika di balik kode Anda.

Mari kita bedah nilai `accel` ($a_{lin\_forward}$) dari IMU MPU-6050 saat dipasang di tubuh manusia (atau kacamata):

### 1. Secara Fisika (Fakta IMU)
Jika kacamata menghadap lurus ke depan:
*   Saat pengguna **memulai langkah (akselerasi / *speeding up*)**, nilai $a_{lin}$ pada sumbu maju (biasanya Z atau X tergantung orientasi sensor) akan bernilai **Positif (+)**.
*   Saat pengguna **mengerem atau berhenti (deselerasi / *slowing down*)**, nilai $a_{lin}$ akan terlempar ke arah berlawanan sehingga bernilai **Negatif (-)**.

### 2. Apa Dampaknya pada Formula Kita?
Di draf awal, Anda menulis:
$d_B = v_{avg} \cdot t_{step} \mathbf{-} \frac{1}{2} \mathbf{\vert} a_{lin} \mathbf{\vert} \cdot t_{step}^2$

Jika kita menggunakan tanda **minus** dan **nilai absolut ($\vert \dots \vert$)**, maka *baik saat pengguna melaju cepat maupun melambat*, jarak pengeremannya ($d_B$) akan selalu dikurangi. Ini **BERBAHAYA** secara navigasi!

Jika pengguna sedang melaju semakin cepat (akselerasi positif), jarak pengereman yang mereka butuhkan untuk berhenti harusnya **bertambah jauh**, bukan berkurang.

### 3. Koreksi Formula yang Benar
Oleh karena itu, tanda mutlak ($\vert \dots \vert$) harus kita buang, dan kita gunakan tanda **plus (+)** sesuai hukum murni Newton ($s = v_0 t + \frac{1}{2}at^2$). 

Formulanya harus menjadi:
$$d_B = v_{avg} \cdot t_{step} \mathbf{+} \frac{1}{2} a_{lin} \cdot t_{step}^2$$

**Skenario Bukti:**
1. **Pengguna makin cepat (Akselerasi, $a_{lin}$ = +2):**
   $d_B = (v \cdot t) + \frac{1}{2}(+2)t^2$ 
   👉 Hasilnya, Jarak pengereman ($d_B$) **bertambah panjang**. Alarm akan berbunyi lebih cepat karena tubuh butuh ruang lebih luas untuk berhenti. (SANGAT AMAN).
2. **Pengguna ngerem (Deselerasi, $a_{lin}$ = -2):**
   $d_B = (v \cdot t) + \frac{1}{2}(-2)t^2$ 
   👉 Hasilnya, Jarak pengereman ($d_B$) **memendek**. Alarm tidak perlu buru-buru berbunyi karena tubuh pengguna sudah dalam posisi melambat.

### Kesimpulan
Nilai accel saat melangkah maju adalah **Positif**.
Oleh karena itu, saya akan merevisi sedikit rumusan di `BAB III.md` untuk membuang tanda mutlak $\vert a_{lin} \vert$ dan mengubah minus menjadi plus, agar perhitungan fisika (dan kodenya nanti) bisa bereaksi secara realistis (memanjang saat akselerasi, memendek saat deselerasi).

Apakah penjelasan logika positif/negatif ini masuk akal bagi Anda, Pak Aan? Jika setuju, saya sesuaikan sedikit Bab 3-nya, lalu kita tuangkan ke dalam `NavigationCoordinator.kt`.