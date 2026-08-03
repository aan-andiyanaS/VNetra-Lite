# [Feature] Refactor LatencyLogger → SessionDataLogger (CSV Master per-Frame)

## Background

`LatencyLogger.kt` hanya merekam agregat statistik latensi (MIN/AVG/MAX per-detik).
Variabel formula navigasi — `v_raw`, `v_avg`, `d_obj`, `M_buffer`, dan `T` (Threshold) —
**tidak pernah direkam**, padahal seluruh variabel tersebut wajib memiliki bukti data
yang tertelusur (*traceable*) untuk pengujian sistem pada Bab 4 skripsi.

Selain itu, nama `LatencyLogger` menyesatkan karena tanggung jawab barunya
jauh lebih luas dari sekadar pencatatan latensi.

## Perubahan

### File Baru
- `util/SessionDataLogger.kt` — menggantikan `LatencyLogger.kt`
  - Data class `SessionFrame`: satu baris = satu frame evaluasi (~30 Hz)
  - Data class `LatencyMetrics`: dipertahankan untuk UI Flow (tidak berubah)
  - Penulisan CSV **langsung per-frame** (hapus ring-buffer agregasi)
  - Output file: `Documents/VNetra_Logs/VNetra_Session_<timestamp>.csv`

### File Diubah
- `service/StreamService.kt`
  - Ganti `latencyLogger: LatencyLogger` → `sessionDataLogger: SessionDataLogger`
  - Pindahkan state latensi (`dynamicTtsLatency`, `currentSerialLatencyMs`, `currentBtLatencyMs`) ke instance variables ber-`@Volatile`
  - Rekam `SessionFrame` di dalam `evaluateObstacles()` setelah formula selesai dihitung
  - Hapus coroutine logging terpisah (disederhanakan)

### File Dihapus
- `util/LatencyLogger.kt`

## Schema CSV Master (15 Kolom)

```
timestamp_ms, elapsed_s, d_obj_mm,
v_raw_mmps, v_avg_mmps,
m_buffer_mm, threshold_T_mm,
alert_triggered, alert_text,
latency_hw_ms, latency_serial_ms, latency_algo_ms,
latency_tts_ms, latency_bt_ms, latency_total_ms
```

## Catatan (Ponytail)

- `v_raw_mmps` ≈ `v_avg_mmps` karena keduanya adalah output EWMA dari `NavigationCoordinator`.
  vRaw sebelum EWMA tidak diekspos via API publik; perubahan API tersebut adalah *out-of-scope*
  untuk issue ini. Kolom `v_raw_mmps` dipertahankan sebagai placeholder untuk iterasi berikutnya
  jika `NavigationCoordinator` mengekspos `vRawEma` secara terpisah.

## Checklist

- [x] `SessionDataLogger.kt` dibuat
- [x] `StreamService.kt` diperbarui
- [x] `LatencyLogger.kt` dihapus
- [ ] Build `assembleDebug` sukses
- [ ] Verifikasi CSV terbuat saat sesi aktif
