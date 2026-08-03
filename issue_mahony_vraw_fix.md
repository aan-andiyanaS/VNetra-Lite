# [Fix] Correct Mahony AHRS References & Expose Genuine vRaw (pre-EWMA) to CSV

## Background

Commit `32ef42e` (SessionDataLogger) merekam kolom `v_raw_mmps` dan `v_avg_mmps` dengan nilai **identik**, karena keduanya mengambil `physics.vAvg`. Selain itu, komentar di `NavigationCoordinator.kt` menyebut "EKF" dan "EMA" padahal implementasi nyata menggunakan **Mahony AHRS** dan **EWMA**.

## Masalah

1. **Komentar menyesatkan** — `isConverged` tidak menjelaskan bahwa ini adalah warmup counter Mahony (100 frame ≈ 2.5 detik), bukan norma kovarians EKF
2. **Terminologi inkonsisten** — `EMA` di komentar kode ≠ `EWMA` di dokumen formula (formula-matematis-v9.4.md)
3. **Data CSV tidak akurat** — `v_raw_mmps` = `v_avg_mmps` (selalu identik), sehingga efek EWMA tidak terlihat dalam data pengujian Bab 4

## Perubahan

### `util/NavigationCoordinator.kt`
- Tambah field `lastVRaw: Float` untuk menyimpan nilai kecepatan pendekatan **sebelum** EWMA diterapkan
- Update `ObstaclePhysics` data class: tambah field `vRaw` (pre-EWMA) di samping `vAvg` (post-EWMA)
- Perbaiki komentar `isConverged`: jelaskan secara eksplisit bahwa ini **Mahony AHRS warmup** = 100 frame × 5ms × 5-tick ≈ 2.5 detik
- Ganti semua `EMA` → `EWMA` di komentar agar konsisten dengan dokumentasi formula
- Reset `lastVRaw` di `resetPhysics()` dan `resetDObjSmoothed()`

### `service/StreamService.kt`
- Ganti `vRawMmps = physics.vAvg` → `vRawMmps = physics.vRaw`
- Perbaiki komentar yang salah (sebelumnya menyebut "ponytail: vRaw ≈ vAvg setelah EWMA")
- Perbaiki indentasi blok perekaman CSV

## Dampak pada Data Pengujian

| Kolom CSV | Sebelum | Sesudah |
|---|---|---|
| `v_raw_mmps` | = `v_avg_mmps` (tidak berguna) | Kecepatan mentah per-frame SEBELUM EWMA |
| `v_avg_mmps` | Kecepatan post-EWMA (benar) | Kecepatan post-EWMA (tetap benar) |

Dengan perbaikan ini, grafik di Bab 4 dapat menunjukkan **selisih `v_raw` vs `v_avg`** sebagai bukti efektivitas filter EWMA.

## Catatan

Perubahan ini **tidak mengubah logika navigasi** sama sekali. Formula G, threshold T, dan keputusan TTS alert tetap menggunakan `vAvg` seperti sebelumnya. Ini murni perbaikan observabilitas data dan konsistensi komentar kode.

## Commits

- `16b1a10` — fix: correct Mahony references and expose genuine vRaw (pre-EWMA) to CSV

## Checklist

- [x] `lastVRaw` ditambahkan ke `NavigationCoordinator`
- [x] `ObstaclePhysics.vRaw` diekspos
- [x] Komentar `isConverged` menjelaskan Mahony warmup
- [x] Semua `EMA` → `EWMA` di komentar kode
- [x] `StreamService` menggunakan `physics.vRaw` untuk CSV
- [x] Commit `16b1a10` sudah di-push ke branch `coba`
