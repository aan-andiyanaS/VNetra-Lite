# Saran Pengembangan (Future Work) - VNetra-Lite

Dokumen ini berisi temuan dari audit mendalam menggunakan pendekatan *Doubt-Driven Development*. Celah-celah di bawah ini bukan *bug* fatal untuk penggunaan harian standar, melainkan *edge-cases* fisika ekstrem yang dapat dijadikan dasar **Saran Pengembangan** (Bab 5 Skripsi) untuk penelitian VNetra generasi selanjutnya.

## 1. Integrasi Rotasi Matriks 3D (Kompensasi Kebutaan Gravitasi)
**Kondisi Saat Ini:**
Pemotongan *grid* 8x8 VL53L5CX menjadi arah jam (Kiri, Tengah, Kanan) bersifat statis terhadap sumbu sensor. Sistem berasumsi kepala pengguna selalu tegak lurus dengan gravitasi bumi (Roll ≈ 0°).
**Risiko Fisik:**
Jika pengguna memiringkan kepalanya 90 derajat hingga menempel di bahu, orientasi *grid* akan ikut miring. Rintangan di tanah/lantai bisa terbaca masuk ke "Kolom Kiri" atau "Kolom Kanan".
**Saran Pengembangan:**
Sistem selanjutnya perlu mengalikan matriks *array* kedalaman 8x8 dengan matriks rotasi (Rotation Matrix 3D atau Quaternion) menggunakan data Roll dari MPU6050 sebelum dilakukan penentuan arah jam. Dengan ini, pembacaan ruang spasial akan selalu terkunci ke horizon bumi terlepas dari kemiringan kepala pengguna.

## 2. Peningkatan "Semantic Memory" ke Sistem SLAM
**Kondisi Saat Ini:**
Sistem memori spasial pada `NavigationCoordinator.kt` hanyalah "State Machine" untuk memfilter *spam* (Noise Gate). Sistem melacak posisi relatif rintangan *hanya* sesaat setelah rintangan tersebut muncul, namun memori ini segera dihapus ketika pengguna berpaling atau berjalan bebas.
**Saran Pengembangan:**
Sistem masa depan dapat berevolusi menjadi VSLAM (*Visual Simultaneous Localization and Mapping*) di mana VNetra mampu mengingat titik rintangan di masa lalu dalam koordinat dunia nyata (X, Y, Z). Jika pengguna kembali ke lokasi yang sama, VNetra sudah mengenali struktur rintangannya sebelum sensor melakukan pemindaian penuh.

## 3. Mitigasi Yaw Drift (Rotasi Kepala Sangat Lambat)
**Kondisi Saat Ini:**
Pada `NavigationCoordinator.kt`, terdapat *deadband* 4°/s (`abs(yawRate) < 4.0f`) untuk menyaring *noise* statis dari giroskop MPU6050.
**Risiko Fisik:**
Jika pengguna menolehkan kepalanya dengan gerakan yang sangat lambat (di bawah ambang batas 4°/s) dalam durasi yang panjang, sistem tidak mendeteksi akumulasi perubahan Yaw. Akibatnya, sistem mengira pengguna masih menghadap arah rintangan yang lama, sehingga berpotensi membungkam peringatan untuk rintangan baru (False Negative) akibat memori semantik yang tidak ter-reset.
**Saran Pengembangan:**
Menggunakan *Kalman Filter* atau *Madgwick Filter* yang lebih presisi, dipadukan dengan sensor Magnetometer (Kompas) untuk melacak *absolute heading* (Yaw absolut) terhadap Kutub Utara Bumi, sehingga arah hadap kepala mutlak terukur meskipun rotasinya sangat lambat.

## 4. Evaluasi Translasi Berbasis Integrasi Kecepatan (Ilusi Linear Acceleration)
**Kondisi Saat Ini:**
Sistem mengenali pergerakan translasi (berjalan menjauh) dengan syarat `aLin > 1.0f`. 
**Risiko Fisik:**
Berdasarkan Hukum Newton, pergerakan linear yang sangat mulus (kecepatan stabil, tanpa percepatan) akan membuat `aLin` mendekati nol. Jika pengguna berjalan tanpa guncangan badan sama sekali, variabel `openSpaceWalkFrames` tidak akan terpicu, dan memori rintangan lama tidak ter-reset.
**Saran Pengembangan:**
Sistem tidak boleh bergantung hanya pada akselerasi sesaat, melainkan perlu menghitung kecepatan absolut dengan mengintegrasikan nilai akselerasi (jika tingkat presisi IMU ditingkatkan), atau dipadukan dengan sensor Pedometer eksternal/GPS untuk mengetahui apakah pengguna benar-benar berpindah posisi secara translasi.
