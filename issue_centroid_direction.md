# Fix: Arah Jam Obstacle Menggunakan Centroid Massa, Bukan Histogram Peak

## Deskripsi

`SpatialMappingUtils.analyzeTerrain()` memiliki docstring yang menyebut "Centroid Massa" sebagai dasar penentuan arah jam, namun implementasinya menggunakan **histogram peak** (kolom dengan jumlah sel terbanyak) — bukan centroid yang sebenarnya.

## Root Cause

```kotlin
// SEBELUM (histogram peak — bukan centroid):
val colCounts = IntArray(8)
for (cell in dangerCells) { colCounts[cell.col]++ }
var bestCol = 3; var maxCount = -1
for (c in 0..7) {
    if (colCounts[c] > maxCount) { maxCount = colCounts[c]; bestCol = c }
    // tie-breaking: pilih yang paling dekat ke 3.5
}
val clockDir = getColumnClockDirection(bestCol)
```

## Contoh Kasus Gagal

**Skenario: Tembok lebar penuh (8 kolom, merata)**
- `colCounts = [2, 2, 2, 2, 2, 2, 2, 2]` — semua sama
- Iterasi pertama: `col 0` memenuhi `count > maxCount` (-1) → `bestCol = 0`
- Tie-breaking untuk col 1-7 tidak terpicu karena count tidak pernah melebihi
- **Hasil: clockDir = "arah 10" (kiri) ← SALAH**
- **Seharusnya: "arah 12" (tengah)**

**Skenario: Obstacle di kiri-kanan (bimodal, kolom 0-1 dan 6-7)**
- Histogram peak: col 0 atau col 6 (tie, pilih yang terdekat ke 3.5 = col 1 atau 6)
- **Centroid: (0+1+6+7)/4 = 3.5 → col 3-4 = "arah 12" — menunjuk ke tengah gap!**

## Fix

```kotlin
// SESUDAH (centroid sejati — sesuai docstring):
val centroidCol = dangerCells.map { it.col }.average().roundToInt().coerceIn(0, 7)
val clockDir = getColumnClockDirection(centroidCol)
```

Centroid kolom = rata-rata posisi kolom seluruh danger cells. Ini:
- **Matematis benar**: center of mass, bukan mode distribusi
- **Lebih sederhana**: 22 baris → 2 baris
- **Konsisten dengan docstring** yang sudah menyebut "Centroid Massa"

## Hubungan dengan Logika Tembok/Halangan

Deteksi tipe (`isWall`) sudah menggunakan `distinctRows` (vertikal ≥ 4 baris = tembok).
Fix ini **tidak mengubah deteksi tipe** — hanya memperbaiki arah jam setelah tipe ditentukan.

Pipeline tetap:
1. `extractCloseCells()` → EMA + holdover filtering
2. `nearestDist` = minimum absolut semua sel valid
3. `dangerCells` = filter ≤ nearestDist + 300mm
4. `distinctRows` → tembok (vertikal) vs halangan (acak)
5. **[FIX]** `centroidCol` → `clockDirection` (benar secara spasial)

## Files Changed
- `app/src/main/java/com/airi/vnetra/util/SpatialMappingUtils.kt`
  - Hapus 22 baris histogram + loop
  - Tambah 2 baris centroid calculation
