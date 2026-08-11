# Diagram Aktivitas (Activity Diagram) VNetra-Lite

Pemodelan alur kerja sistem VNetra-Lite digambarkan menggunakan *Activity Diagram* untuk memperjelas interaksi sekuensial antara pengguna, perangkat keras (Kacamata ESP32), dan Aplikasi Android. *Activity Diagram* pada sistem ini dibagi menjadi dua skenario utama, yaitu skenario konfigurasi jaringan (Provisioning) oleh Pendamping, dan skenario navigasi spasial oleh Tunanetra.

---

## 1. Activity Diagram: Setup Jaringan (Skenario Pendamping)

Berdasarkan gambar di bawah, alur konfigurasi jaringan (*Provisioning*) dimulai ketika Pendamping menyalakan Kacamata ESP32 dan membuka Aplikasi Android. Aplikasi akan melakukan pemindaian (*scanning*) dan terkoneksi secara otomatis dengan Kacamata melalui Bluetooth Low Energy (BLE). Setelah terkoneksi, Pendamping memberikan perintah melalui antarmuka aplikasi agar Kacamata memindai jaringan WiFi yang tersedia. Kacamata kemudian mengirimkan daftar WiFi kembali ke aplikasi untuk dipilih oleh Pendamping. Setelah kredensial WiFi dimasukkan, Kacamata akan terkoneksi ke jaringan lokal (Router/Hotspot) dan mengirimkan Alamat IP lokalnya kembali ke aplikasi sebagai tanda bahwa sistem siap untuk masuk ke tahap pemantauan utama.

```mermaid
flowchart TD
    %% Pendefinisian Swimlane
    subgraph Pendamping [Pendamping]
        direction TB
        P_Start(("Mulai"))
        P_NyalakanKacamata["Menyalakan Kacamata"]
        P_NyalakanHP["Menyalakan Bluetooth HP"]
        P_BukaApp["Membuka Aplikasi Android"]
        P_TekanScan["Menekan Tombol Scan WiFi"]
        P_PilihWiFi["Memilih WiFi & Masukkan Password"]
        P_TekanStream["Menekan Tombol 'View Sensor'"]
        P_End(("Selesai"))
    end

    subgraph Kacamata [Kacamata ESP32]
        direction TB
        K_BLEMenyala["Modul BLE Aktif / Advertising"]
        K_KirimListWiFi["Memindai & Mengirim Daftar WiFi via BLE"]
        K_KonekWiFi["Koneksi ke Jaringan WiFi"]
        K_KirimIP["Mengirim Alamat IP Lokal via BLE"]
    end

    subgraph Aplikasi [Aplikasi Android]
        direction TB
        A_ScanBLE["Memindai & Menghubungkan BLE Otomatis"]
        A_KirimScan["Mengirim Perintah Scan WiFi ke ESP32"]
        A_TampilList["Menampilkan Daftar WiFi di Layar"]
        A_KirimKredensial["Mengirim Kredensial via BLE"]
        A_SimpanIP["Menerima Alamat IP & Mengaktifkan Tombol Utama"]
        A_PindahHalaman["Beralih ke Halaman Navigasi Utama"]
    end

    %% Relasi Alur Kerja (Kronologis)
    P_Start --> P_NyalakanKacamata
    P_Start --> P_NyalakanHP
    
    P_NyalakanKacamata --> K_BLEMenyala
    P_NyalakanHP --> P_BukaApp
    P_BukaApp --> A_ScanBLE
    K_BLEMenyala -.-> A_ScanBLE
    
    A_ScanBLE --> P_TekanScan
    P_TekanScan --> A_KirimScan
    A_KirimScan --> K_KirimListWiFi
    K_KirimListWiFi -.-> A_TampilList
    
    A_TampilList --> P_PilihWiFi
    P_PilihWiFi --> A_KirimKredensial
    A_KirimKredensial --> K_KonekWiFi
    K_KonekWiFi --> K_KirimIP
    K_KirimIP -.-> A_SimpanIP
    
    A_SimpanIP --> P_TekanStream
    P_TekanStream --> A_PindahHalaman
    A_PindahHalaman --> P_End
    
    %% Styling
    classDef user fill:#e3f2fd,stroke:#1565c0,stroke-width:2px;
    classDef device fill:#fff3e0,stroke:#e65100,stroke-width:2px;
    classDef app fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px;
    
    class P_NyalakanKacamata,P_NyalakanHP,P_BukaApp,P_TekanScan,P_PilihWiFi,P_TekanStream user;
    class K_BLEMenyala,K_KirimListWiFi,K_KonekWiFi,K_KirimIP device;
    class A_ScanBLE,A_KirimScan,A_TampilList,A_KirimKredensial,A_SimpanIP,A_PindahHalaman app;
```

---

## 2. Activity Diagram: Navigasi Spasial (Skenario Tunanetra)

Gambar di bawah mengilustrasikan alur pemrosesan navigasi spasial saat sistem dioperasikan oleh Tunanetra. Alur dimulai ketika Tunanetra mengaktifkan Kacamata dan menyalakan Hotspot pada ponsel cerdas, sehingga Kacamata dapat terkoneksi secara otomatis. Saat aplikasi dibuka, Kacamata secara konstan mentransmisikan data matriks Time-of-Flight (ToF) dan Inertial Measurement Unit (IMU) ke aplikasi Android. Aplikasi bertindak sebagai pusat komputasi yang menjalankan algoritma pemetaan spasial dan evaluasi fisika (*Safe Stopping Distance*). Ketika batas jarak aman terlampaui, sistem akan memastikan bahwa data lolos filter *anti-spam* (jeda waktu), lalu merekam log latensi perhitungan, menentukan arah jam dari objek, dan memberikan umpan balik berupa suara *Text-to-Speech* (TTS) melalui *earphone* kepada Tunanetra.

```mermaid
flowchart TD
    %% Pendefinisian Swimlane
    subgraph Tunanetra [Tunanetra]
        direction TB
        T_Start(("Mulai"))
        T_NyalakanKacamata["Menyalakan Kacamata"]
        T_NyalakanHotspot["Menyalakan Hotspot Smartphone"]
        T_BukaApp["Membuka Aplikasi Android"]
        T_DengarTTS["Mendengar Peringatan Suara TTS"]
        T_End(("Selesai"))
    end

    subgraph Kacamata [Kacamata ESP32]
        direction TB
        K_KoneKHotspot["Menyambung Otomatis ke Hotspot"]
        K_Streaming["Mentransmisikan Data ToF 8x8 & IMU via Soket"]
    end

    subgraph Aplikasi [Aplikasi Android]
        direction TB
        A_TerimaData["Menerima Aliran Data UDP/WebSocket"]
        A_Spasial["Menjalankan Pemetaan Spasial 8x8 dengan Data ToF & IMU"]
        A_Fisika["Mengevaluasi Jarak Aman (SSD) dan TTC"]
        A_LogCSV["Merekam Data Log Perhitungan & Latensi"]
        A_ArahJam["Menentukan Arah Jam Objek"]
        A_KirimTTS["Mengirim Sintesis Suara Jarak & Arah Jam"]
    end

    %% Relasi Alur Kerja (Kronologis)
    T_Start --> T_NyalakanKacamata
    T_Start --> T_NyalakanHotspot
    
    T_NyalakanKacamata --> K_KoneKHotspot
    T_NyalakanHotspot --> K_KoneKHotspot
    
    K_KoneKHotspot --> K_Streaming
    T_NyalakanHotspot --> T_BukaApp
    T_BukaApp --> A_TerimaData
    K_Streaming -.-> A_TerimaData
    
    A_TerimaData --> A_Spasial
    A_Spasial --> A_Fisika
    A_Fisika --> A_ArahJam
    
    %% Alur Pararel untuk Logging dan TTS
    A_ArahJam --> A_LogCSV
    A_ArahJam --> A_KirimTTS
    
    A_KirimTTS -.-> T_DengarTTS
    
    T_DengarTTS --> T_End
    A_LogCSV --> T_End
    
    %% Styling
    classDef user fill:#e3f2fd,stroke:#1565c0,stroke-width:2px;
    classDef device fill:#fff3e0,stroke:#e65100,stroke-width:2px;
    classDef app fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px;
    
    class T_NyalakanKacamata,T_NyalakanHotspot,T_BukaApp,T_DengarTTS user;
    class K_KoneKHotspot,K_Streaming device;
    class A_TerimaData,A_Spasial,A_Fisika,A_LogCSV,A_ArahJam,A_KirimTTS app;
```
