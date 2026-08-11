# !pip install pandas matplotlib numpy
import os, glob
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
from matplotlib.lines import Line2D

# ── Gaya plot: Q1 Journal Standard (IEEE / Elsevier) ──────────────────────
# Mengacu pada IEEE Author Guidelines & Elsevier Artwork Instructions:
# - Latar putih, teks hitam, sans-serif (Arial equiv.), minimal ink.
# - Grid tipis abu-abu, tanpa frame tebal, legend bersih.
# - Resolusi cetak 300 DPI, ukuran font 9-10 pt.
plt.rcParams.update({
    # Canvas
    'figure.facecolor':   'white',
    'axes.facecolor':     'white',
    'axes.edgecolor':     '#333333',
    'axes.linewidth':     0.8,
    # Text
    'text.color':         '#111111',
    'axes.labelcolor':    '#111111',
    'xtick.color':        '#333333',
    'ytick.color':        '#333333',
    'axes.labelsize':     10,
    'xtick.labelsize':    9,
    'ytick.labelsize':    9,
    'axes.titlesize':     10,
    'axes.titleweight':   'bold',
    # Font — sans-serif mirip Arial (IEEE standard)
    'font.family':        'sans-serif',
    'font.sans-serif':    ['DejaVu Sans', 'Arial', 'Helvetica'],
    # Grid — tipis, tidak mengganggu data
    'axes.grid':          True,
    'grid.color':         '#CCCCCC',
    'grid.linewidth':     0.5,
    'grid.linestyle':     '--',
    'grid.alpha':         0.7,
    # Tick
    'xtick.direction':    'in',
    'ytick.direction':    'in',
    'xtick.major.size':   3.5,
    'ytick.major.size':   3.5,
    # Legend
    'legend.facecolor':   'white',
    'legend.edgecolor':   '#AAAAAA',
    'legend.fontsize':    9,
    'legend.framealpha':  0.9,
    # Savefig
    'figure.dpi':         100,
    'savefig.dpi':        300,
    'savefig.bbox':       'tight',
    'savefig.facecolor':  'white',
})
os.makedirs('output', exist_ok=True)

# ── Palet warna Q1 Journal (color-blind safe, WCAG-compliant) ───────────────
# Menggunakan subset dari Wong (2011) color-blind safe palette:
# Nature Methods 8(6):441 — direkomendasikan untuk publikasi ilmiah.
C_THRESHOLD = '#0072B2'   # Biru tua    — Threshold T (IBM Blue)
C_DISTANCE  = '#D55E00'   # Oranye tua  — Jarak objek (vermillion)
C_THEORY    = '#E69F00'   # Oranye muda — Kurva teoritis (orange)
C_ALERT     = '#CC79A7'   # Ungu muda   — Momen alert
C_SAFE      = '#009E73'   # Hijau tua   — d0 (bluish green)
C_MAX       = '#D62728'   # Merah       — T_max
C_SCATTER   = '#0072B2'   # Biru        — Scatter data lapangan
print('Lingkungan analisis Q1-journal siap.')
# Sesuaikan dengan nilai di VNetraConfig.kt Anda
D0     = 1000    # BASE_WARNING_DIST_MM (mm)
T_MAX  = 4000    # MAX_THRESHOLD_MM (mm)
T_R    = 1.3     # PERCEPTION_REACTION_TIME_SEC — Kovacs & Nagy [44]
T_STEP = 0.63    # STEP_DURATION_SEC — Knoblauch et al. [42]; Winter [45]
print(f'Config: d0={D0}mm | T_max={T_MAX}mm | t_R={T_R}s | t_step={T_STEP}s')
def generate_dummy(fname='VNetra_Session_DUMMY.csv', duration_s=60, fps=15):
    n = duration_s * fps
    t = np.linspace(0, duration_s, n)
    np.random.seed(42)  # reproducible
    # Profil kecepatan pendekatan (mm/s)
    vp = np.zeros(n)
    vp[fps*5:fps*25]  = np.linspace(0, 1200, fps*20)
    vp[fps*25:fps*40] = 1200
    vp[fps*40:fps*50] = np.linspace(1200, 0, fps*10)
    v_raw = np.maximum(vp + np.random.normal(0, 80, n), 0)
    v_avg = pd.Series(v_raw).ewm(alpha=0.45).mean().values
    # M_buffer = 0.5 * a_lin * t_step^2 (Kinematika Newton, Pers. 2.9)
    a_lin = np.clip(0.6 + np.random.normal(0, 0.12, n), 0.1, 1.5)  # m/s^2
    m_buf = 0.5 * a_lin * 1000 * (T_STEP ** 2)   # konversi ke mm
    # SSD = d_R + d_B = v*t_R + M_buffer (Pers. 2.7)
    ssd   = v_avg * T_R + m_buf
    rv    = T_MAX - D0
    # Threshold adaptif: T = d0 + (T_max-d0)*tanh(SSD/(T_max-d0))
    thresh = np.clip(D0 + rv * np.tanh(ssd / rv), D0, T_MAX).astype(int)
    d_obj  = np.clip(5000 - t*65 + np.random.normal(0, 40, n), 600, 5500).astype(int)
    alert  = (d_obj < thresh).astype(int)
    lhw  = np.random.randint(8,  28,  n)
    lnet = np.random.randint(12, 75,  n)
    lal  = np.random.randint(2,  7,   n)
    ltts = np.where(alert, np.random.randint(90, 230, n), 0)
    lbt  = np.random.randint(1,  5,   n)
    df = pd.DataFrame({
        'timestamp_ms': (t*1000).astype(int)+1700000000000,
        'elapsed_s':    np.round(t, 2),
        'd_obj_mm':     d_obj,
        'v_raw_mmps':   np.round(v_raw, 2),
        'v_avg_mmps':   np.round(v_avg, 2),
        'm_buffer_mm':  np.round(m_buf, 2),
        'threshold_T_mm':  thresh,
        'alert_triggered': alert,
        'alert_text':   np.where(alert, 'hati-hati halangan di depan', ''),
        'latency_hw_ms':   lhw,  'latency_net_ms':  lnet,
        'latency_algo_ms': lal,  'latency_tts_ms':  ltts,
        'latency_bt_ms':   lbt,  'latency_total_ms': lhw+lnet+lal+ltts+lbt,
        'packet_loss_count': np.random.poisson(0.25, n),
    })
    df.to_csv(fname, index=False)
    n_alert = int(alert.sum())
    print(f'Dummy CSV dibuat: {fname} | {n} frame | Alert: {n_alert} frame')
    return df

df = generate_dummy()
df.head(3)
# files = sorted(glob.glob('VNetra_Session_*.csv'))
# if not files:
#     raise FileNotFoundError('Tidak ada CSV! Salin dari Android ke folder ini.')
# print('File ditemukan:', files)
# df = pd.read_csv(files[-1])
# print(f'Loaded: {files[-1]} | {len(df)} baris')
# df.head()
fig, ax = plt.subplots(figsize=(7.2, 3.5))  # IEEE two-column width

# Data utama
ax.plot(df['elapsed_s'], df['threshold_T_mm'],
        color=C_THRESHOLD, lw=2.0, alpha=0.95, label='Threshold $T$ (mm)', zorder=3)
ax.fill_between(df['elapsed_s'], df['threshold_T_mm'],
                alpha=0.07, color=C_THRESHOLD, zorder=2)
ax.plot(df['elapsed_s'], df['d_obj_mm'],
        color=C_DISTANCE, lw=1.5, alpha=0.85, label='Jarak Objek $d_{obj}$ (mm)', zorder=3)

# Garis referensi
ax.axhline(D0,    color=C_SAFE, lw=1.3, ls='--', alpha=0.9,
           label=f'$d_0 = {D0}$ mm (zona aman minimum)')
ax.axhline(T_MAX, color=C_MAX,  lw=1.3, ls=':', alpha=0.9,
           label=f'$T_{{max}} = {T_MAX}$ mm')

# Marker momen alert
alerts = df[df['alert_triggered'] == 1]
n_alert = len(alerts)
if n_alert > 0:
    ax.scatter(alerts['elapsed_s'], alerts['d_obj_mm'],
               color=C_ALERT, s=30, zorder=6, marker='^',
               edgecolors='black', linewidths=0.4,
               label=f'Peringatan terpicu ({n_alert} frame)')

# Anotasi fase (shading tipis, teks hitam)
ax.axvspan(0,   5,  alpha=0.06, color='#CCCCCC')
ax.axvspan(5,  40,  alpha=0.06, color='#DDEEFF')
ax.axvspan(40, 60,  alpha=0.06, color='#FFE8DD')
ax.text(1,  T_MAX*0.97, 'Diam',     color='#555555', fontsize=7.5, va='top')
ax.text(6,  T_MAX*0.97, 'Berjalan', color='#555555', fontsize=7.5, va='top')
ax.text(41, T_MAX*0.97, 'Melambat', color='#555555', fontsize=7.5, va='top')

# Format
ax.set_title('Gambar 4.1. Perilaku Threshold Adaptif $T$ dan Jarak Objek $d_{obj}$\n'
             'terhadap Waktu Sesi Pengujian', pad=14)
ax.set_xlabel('Waktu Sesi, $t$ (detik)')
ax.set_ylabel('Jarak, $d$ (mm)')
ax.set_xlim(df['elapsed_s'].min(), df['elapsed_s'].max())
ax.set_ylim(0, T_MAX * 1.12)
ax.yaxis.set_major_formatter(ticker.StrMethodFormatter('{x:,.0f}'))
ax.legend(loc='upper right', ncol=2)
ax.spines['top'].set_visible(False)
ax.spines['right'].set_visible(False)

fig.text(0.5, -0.04,
    f'Keterangan: $d_0={D0}$ mm, $T_{{max}}={T_MAX}$ mm, $t_R={T_R}$ s, $t_{{step}}={T_STEP}$ s. '
    f'Formula: $T = d_0 + (T_{{max}}-d_0)\\cdot\\tanh(SSD/(T_{{max}}-d_0))$ [Pers. 2.11].',
    ha='center', fontsize=8, color='#8080A0', style='italic')

plt.tight_layout()
plt.savefig('output/Fig41_threshold_vs_waktu.png')
plt.show()
print('Gambar 4.1 disimpan: output/Fig41_threshold_vs_waktu.png')
fig, ax = plt.subplots(figsize=(3.5, 3.5))  # IEEE single-column

# Scatter data lapangan
ax.scatter(df['v_avg_mmps'], df['threshold_T_mm'],
           alpha=0.25, s=6, color=C_SCATTER, edgecolors='none',
           label='Data lapangan (per frame)', zorder=2)

# Kurva teoritis Tanh
v_line   = np.linspace(0, max(df['v_avg_mmps'].max() * 1.08, 1600), 600)
m_avg    = df['m_buffer_mm'].mean()   # rata-rata M_buffer dari data nyata
ssd_line = v_line * T_R + m_avg       # SSD = d_R + d_B
rv       = float(T_MAX - D0)
t_theory = D0 + rv * np.tanh(ssd_line / rv)
ax.plot(v_line, t_theory, color=C_THEORY, lw=2.5, zorder=4,
        label='Kurva teoritis Tanh')

# Garis batas referensi
ax.axhline(D0,    color=C_SAFE, lw=1.2, ls='--', alpha=0.9)
ax.axhline(T_MAX, color=C_MAX,  lw=1.2, ls='--', alpha=0.9)
ax.text(v_line[-1]*0.04, D0 + 60,     f'$d_0 = {D0}$ mm',    color=C_SAFE, fontsize=9)
ax.text(v_line[-1]*0.04, T_MAX - 200, f'$T_{{max}} = {T_MAX}$ mm', color=C_MAX,  fontsize=9)

# Anotasi properti v=0
t_at_zero = D0 + rv * np.tanh(m_avg / rv)
ax.annotate(f'$v=0$: $T \\approx {int(t_at_zero)}$ mm $\\approx d_0$',
            xy=(0, t_at_zero), xytext=(v_line[-1]*0.28, D0+260),
            arrowprops=dict(arrowstyle='->', color='#A0A0C0', lw=0.9),
            color='#C0C0E0', fontsize=8.5)

# R-squared goodness of fit
t_pred = D0 + rv * np.tanh((df['v_avg_mmps']*T_R + df['m_buffer_mm']) / rv)
ss_res = np.sum((df['threshold_T_mm'] - t_pred)**2)
ss_tot = np.sum((df['threshold_T_mm'] - df['threshold_T_mm'].mean())**2)
r2     = 1 - ss_res/ss_tot if ss_tot > 0 else float('nan')
ax.text(0.97, 0.05, f'$R^2 = {r2:.4f}$',
        transform=ax.transAxes, ha='right', va='bottom',
        color='#E0E0F0', fontsize=10,
        bbox=dict(boxstyle='round,pad=0.4', facecolor='#1A1A2E', edgecolor='#3A3A5C'))

# Format
ax.set_title('Gambar 4.2. Hubungan Kecepatan Pendekatan $v_{avg}$ terhadap\n'
             'Threshold Adaptif $T$ — Validasi Saturasi Fungsi Tanh', pad=14)
ax.set_xlabel('Kecepatan pendekatan terfilter $v_{avg}$ (mm/s)')
ax.set_ylabel('Threshold peringatan adaptif $T$ (mm)')
ax.set_ylim(D0*0.9, T_MAX*1.06)
ax.yaxis.set_major_formatter(ticker.StrMethodFormatter('{x:,.0f}'))
ax.legend(loc='lower right')
ax.spines['top'].set_visible(False)
ax.spines['right'].set_visible(False)

m_avg_val = df['m_buffer_mm'].mean()
fig.text(0.5, -0.04,
    f'$M_{{buffer}}$ rata-rata = {m_avg_val:.1f} mm. '
    r'$SSD = v_{avg}\cdot t_R + \frac{1}{2}a_{lin}\cdot t_{step}^2$ [Pers. 2.7, 2.9].',
    ha='center', fontsize=8, color='#8080A0', style='italic')

plt.tight_layout()
plt.savefig('output/Fig42_velocity_vs_threshold.png')
plt.show()
print(f'Gambar 4.2 disimpan | R2 = {r2:.4f}')
fig, (ax_bar, ax_box) = plt.subplots(1, 2, figsize=(7.2, 3.5),
                                      gridspec_kw={'width_ratios': [2, 1]})

# ── Stacked bar (kiri) ──────────────────────────────────────────────────────
df2 = df.copy()
df2['time_bin'] = pd.cut(df2['elapsed_s'], bins=15)
grp = df2.groupby('time_bin', observed=True)[[
    'latency_hw_ms', 'latency_net_ms', 'latency_algo_ms',
    'latency_tts_ms', 'latency_bt_ms'
]].mean()
x_ticks  = range(len(grp))
x_labels = [f'{i.left:.0f}' for i in grp.index]
lat_cols = ['latency_hw_ms','latency_net_ms','latency_algo_ms','latency_tts_ms','latency_bt_ms']
lat_lbl  = ['Hardware','Jaringan IoT','Algoritma','TTS Audio','Bluetooth']
# Palet warna Q1 — color-blind safe (Wong 2011)
lat_clr  = ['#0072B2','#009E73','#E69F00','#D55E00','#CC79A7']
bottom   = np.zeros(len(grp))
for col, lbl, clr in zip(lat_cols, lat_lbl, lat_clr):
    v = grp[col].fillna(0).values
    ax_bar.bar(x_ticks, v, bottom=bottom, label=lbl, color=clr, alpha=0.88, width=0.72)
    bottom += v
ax_bar.axhline(T_R*1000, color=C_ALERT, lw=1.8, ls='--', alpha=0.9,
               label=f'Batas $t_R = {T_R*1000:.0f}$ ms')
ax_bar.text(len(grp)*0.01, T_R*1000+8, f'Batas $t_R$ [44]', color=C_ALERT, fontsize=8)
ax_bar.set_xticks(x_ticks)
ax_bar.set_xticklabels(x_labels, rotation=45, fontsize=7.5)
ax_bar.set_xlabel('Waktu awal segmen (detik)')
ax_bar.set_ylabel('Latensi rata-rata (ms)')
ax_bar.set_title('(a) Dekomposisi per Segmen Waktu')
ax_bar.legend(fontsize=8, loc='upper left')
ax_bar.spines['top'].set_visible(False)
ax_bar.spines['right'].set_visible(False)

# ── Box plot (kanan) ────────────────────────────────────────────────────────
box_data = [df[c].values for c in lat_cols]
bp = ax_box.boxplot(box_data, patch_artist=True,
                    medianprops=dict(color='black', lw=1.5),
                    whiskerprops=dict(color='#555555', lw=0.8),
                    capprops=dict(color='#555555', lw=0.8),
                    flierprops=dict(marker='.', color='#888888', alpha=0.5, ms=2))
for patch, clr in zip(bp['boxes'], lat_clr):
    patch.set_facecolor(clr); patch.set_alpha(0.6)
ax_box.set_xticks(range(1, 6))
ax_box.set_xticklabels(['HW','Net','Algo','TTS','BT'], fontsize=9)
ax_box.set_ylabel('Latensi (ms)')
ax_box.set_title('(b) Distribusi per Komponen')
ax_box.axhline(T_R*1000, color=C_MAX, lw=1.2, ls='--', alpha=0.8)
ax_box.spines['top'].set_visible(False)
ax_box.spines['right'].set_visible(False)

lat_mean = df['latency_total_ms'].mean()
lat_max  = df['latency_total_ms'].max()
fig.suptitle('Gambar 4.3. Dekomposisi Latensi End-to-End Sistem VNetra-Lite',
             fontsize=12, fontweight='bold', color='#E0E0F0', y=1.01)
fig.text(0.5, -0.05,
    f'Latensi total rata-rata = {lat_mean:.1f} ms | Maks = {lat_max} ms | '
    f'Batas kognitif $t_R = {T_R*1000:.0f}$ ms.',
    ha='center', fontsize=8, color='#555555', style='italic')

plt.tight_layout()
plt.savefig('output/Fig43_latency_decomposition.png')
plt.show()
print(f'Gambar 4.3 disimpan | Latensi rata-rata: {lat_mean:.1f} ms')
fig, (ax_cum, ax_gauge) = plt.subplots(1, 2, figsize=(7.2, 3.0),
                                        gridspec_kw={'width_ratios': [3, 1]})

# ── Kurva kumulatif packet loss ──────────────────────────────────────────────
cum_loss = df['packet_loss_count'].cumsum()
ax_cum.fill_between(df['elapsed_s'], cum_loss, alpha=0.12, color=C_MAX)
ax_cum.plot(df['elapsed_s'], cum_loss, color=C_MAX, lw=1.5)
# Shading momen alert aktif
for t_a in df[df['alert_triggered']==1]['elapsed_s']:
    ax_cum.axvline(t_a, color=C_ALERT, lw=0.4, alpha=0.12)
ax_cum.set_xlabel('Waktu Sesi, $t$ (detik)')
ax_cum.set_ylabel('Packet Loss Kumulatif (paket)')
ax_cum.set_title('(a) Akumulasi Packet Loss terhadap Waktu')
ax_cum.set_xlim(df['elapsed_s'].min(), df['elapsed_s'].max())
custom = [
    Line2D([0],[0], color=C_MAX,   lw=2,   label='Packet Loss Kumulatif'),
    Line2D([0],[0], color=C_ALERT, lw=0.8, alpha=0.6, label='Periode Alert Aktif'),
]
ax_cum.legend(handles=custom, fontsize=9)
ax_cum.spines['top'].set_visible(False)
ax_cum.spines['right'].set_visible(False)

# ── PDR gauge bar ────────────────────────────────────────────────────────────
total_loss   = int(df['packet_loss_count'].sum())
total_frames = len(df)
pdr = (1 - total_loss / (total_frames + total_loss)) * 100
bar_color = C_SAFE if pdr >= 95 else (C_THEORY if pdr >= 90 else C_MAX)

ax_gauge.barh(['PDR'], [100], color='#EEEEEE', height=0.5, edgecolor='#AAAAAA', lw=0.5)
ax_gauge.barh(['PDR'], [pdr], color=bar_color, height=0.5, edgecolor='#555555', lw=0.5)
ax_gauge.axvline(95, color='#333333', lw=1.2, ls='--', alpha=0.8)
ax_gauge.text(96, 0, '95%', color='#333333', fontsize=8, va='center')
ax_gauge.text(pdr/2, 0, f'{pdr:.1f}%', color='white', fontsize=12,
              fontweight='bold', ha='center', va='center')
ax_gauge.set_xlim(0, 100)
ax_gauge.set_xlabel('Packet Delivery Ratio (%)')
ax_gauge.set_title('(b) PDR Total')
ax_gauge.tick_params(axis='y', left=False, labelleft=False)
ax_gauge.spines['top'].set_visible(False)
ax_gauge.spines['right'].set_visible(False)
ax_gauge.spines['left'].set_visible(False)
status = 'MEMENUHI STANDAR' if pdr >= 95 else 'DI BAWAH STANDAR'
ax_gauge.text(50, -0.48, f'{status}\nLoss: {total_loss} paket',
              ha='center', fontsize=8, color='#333333', va='top')

fig.suptitle('Gambar 4.4. Reliabilitas Jaringan UDP: Packet Loss dan PDR',
             fontsize=12, fontweight='bold', color='#E0E0F0', y=1.01)
fig.text(0.5, -0.04,
    f'Total frame: {total_frames} | Loss: {total_loss} paket | PDR = {pdr:.2f}% | '
    f'Standar minimum sistem navigasi real-time: PDR >= 95% [53].',
    ha='center', fontsize=8, color='#555555', style='italic')

plt.tight_layout()
plt.savefig('output/Fig44_packet_loss_pdr.png')
plt.show()
print(f'Gambar 4.4 disimpan | PDR = {pdr:.2f}% | {status}')
total_loss = int(df['packet_loss_count'].sum())
pdr      = (1 - total_loss / (len(df) + total_loss)) * 100
lat_mean = df['latency_total_ms'].mean()
lat_std  = df['latency_total_ms'].std()
lat_med  = df['latency_total_ms'].median()
lat_max  = df['latency_total_ms'].max()
fps_avg  = len(df) / df['elapsed_s'].max()
status_lat = 'OK -- Memenuhi' if lat_mean < T_R*1000 else 'PERLU OPTIMASI'
status_pdr = 'OK' if pdr >= 95 else 'PERLU INVESTIGASI'

print('=' * 62)
print('   TABEL RINGKASAN STATISTIK SESI PENGUJIAN VNetra-Lite')
print('=' * 62)
print(f'  Durasi Sesi                  : {df["elapsed_s"].max():.1f} detik')
print(f'  Total Frame                  : {len(df):,} frame @ {fps_avg:.1f} fps')
print('-' * 62)
print('  [THRESHOLD ADAPTIF]')
print(f'  Threshold Min/Max            : {df["threshold_T_mm"].min():,} / {df["threshold_T_mm"].max():,} mm')
print(f'  Threshold Rata-rata          : {df["threshold_T_mm"].mean():.0f} mm')
print(f'  Kecepatan v_avg Maks         : {df["v_avg_mmps"].max():.0f} mm/s = {df["v_avg_mmps"].max()/1000:.2f} m/s')
print(f'  M_buffer Rata-rata           : {df["m_buffer_mm"].mean():.1f} mm')
print('-' * 62)
print('  [PERINGATAN (ALERT)]')
print(f'  Total Frame Peringatan       : {df["alert_triggered"].sum()} frame')
print(f'  Rasio Frame Peringatan       : {df["alert_triggered"].mean()*100:.1f}%')
print('-' * 62)
print('  [LATENSI END-TO-END]')
print(f'  Latensi Total Mean +/- SD    : {lat_mean:.1f} +/- {lat_std:.1f} ms')
print(f'  Latensi Total Median         : {lat_med:.1f} ms')
print(f'  Latensi Total Maks           : {lat_max} ms')
print(f'  Latensi Jaringan Mean        : {df["latency_net_ms"].mean():.1f} ms')
print(f'  Batas Kognitif t_R           : {T_R*1000:.0f} ms  [Kovacs & Nagy, 2020]')
print(f'  Status                       : {status_lat}')
print('-' * 62)
print('  [RELIABILITAS JARINGAN]')
print(f'  Total Packet Loss            : {total_loss} paket')
print(f'  Packet Delivery Ratio (PDR)  : {pdr:.2f}%')
print(f'  Status (>= 95%?)             : {status_pdr}')
print('=' * 62)