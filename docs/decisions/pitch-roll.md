Viewed BAB%20III%20fix.md:112-124

Saya perlu melihat implementasi `v_head_base` di firmware dan penggunaannya di Android secara bersamaan:

Viewed firmware-vnetra.ino:835-895
Viewed NavigationCoordinator.kt:188-235

Sekarang saya punya semua datanya. Jawaban berdasarkan trace matematika penuh:

---

## Kondisi "Alert Tidak Spam" Berdasarkan Formula $v_{head\_base}$

Kunci ada di baris 192 dan 198-199 Android:

```kotlin
val vHead = vHeadBase * dSmooth                               // (1)
val rawApproachVelocityMmps =
    if (abs(dDelta) < 15) 0f                                  // (2) dead zone
    else ((dDelta / dt) - abs(vHead)).coerceIn(0f, 2000f)    // (3)
```

**Formula lengkap yang terjadi:**

$$v_{rel} = \left(\frac{\Delta d}{\Delta t} - \left|v_{head\_base} \cdot d_{smooth}\right|\right) \cdot \mathbf{1}_{|\Delta d| \geq 15}$$

---

### Alert **tidak spam** jika kondisi ini terpenuhi:

**Kondisi 1 — Dead zone terpenuhi ($|\Delta d| < 15$ mm)**

$$|\Delta d| < 15 \implies v_{rel} = 0 \implies \text{tidak ada alert}$$

Jika jarak tidak berubah lebih dari 15mm antar frame (user diam, objek statis), velocity dipaksa nol.

---

**Kondisi 2 — Kompensasi rotasi kepala menghabiskan seluruh $\Delta d / \Delta t$**

$$\frac{\Delta d}{\Delta t} \leq \left|k_{damp} \cdot |\omega_x^{corr}| \cdot \cos(\theta) \cdot \frac{\pi}{180} \cdot d_{smooth}\right|$$

Dalam bahasa konkrit: jika perubahan jarak ToF **sepenuhnya dapat dijelaskan** oleh gerakan kepala (bukan gerak maju), maka $v_{rel} \leq 0$ → `coerceIn(0f, ...)` → hasil nol → tidak ada alert.

**Contoh numerik:**
```
d_smooth = 1000 mm
ω_x_corr = 10°/s  → k_damp = 0.5 (karena > 5°/s)
θ = 0° → cos(0) = 1.0

v_head_base = 0.5 × 10 × (π/180) = 0.0873 rad/s
vHead       = 0.0873 × 1000     = 87.3 mm/s

→ Jika Δd/Δt ≤ 87.3 mm/s, alert tidak berbunyi
```

---

**Kondisi 3 — EWMA velocity tetap di bawah threshold threshold**

Bahkan jika satu frame menghasilkan `rawVelocity > 0`, EWMA (α=0.4) meredam spike:

$$v_{ema}^{(t)} = 0.4 \cdot v_{raw}^{(t)} + 0.6 \cdot v_{ema}^{(t-1)}$$

Spike tunggal hanya berkontribusi 40% → butuh beberapa frame konsisten untuk mendorong `emaVelocityMmps` ke nilai yang membuat `adaptiveThresholdMm` naik signifikan.

---

### Kesimpulan: Kapan **Paling Aman** dari Spam

```
User diam, kepala tidak bergerak → Δd < 15mm → v_rel = 0 ✓
User menoleh → v_head besar → v_rel dikompensasi → ≈ 0 ✓
Objek statis → Δd ≈ 0 → v_rel = 0 ✓
```

### Kapan **Masih Bisa Spam**

```
k_damp = 0.5 terlalu agresif saat rotasi >5°/s:
kompensasi justru terpotong setengah saat kepala berputar cepat
→ v_head terlalu kecil → v_rel tidak terkompensasi penuh → bisa spam
```

> ⚠️ **Ini adalah celah yang tersisa**: `k_damp = 0.5` memotong kompensasi saat rotasi cepat, padahal justru saat itulah kompensasi paling dibutuhkan. Nilai `k_damp = 0.7–0.8` untuk rotasi cepat akan lebih tepat. Apakah Anda ingin ini dievaluasi juga?