# AGENTS.md — VNetra-Lite (Lightweight Version)
# Project-scoped rules untuk subproject VNetra-Lite + Analysis

## Scope
Workspace ini mencakup:
- Android app lightweight: `app/src/`
- Firmware: `firmware-vnetra/`
- Data analysis: `analysis/`
- Pengujian CSV: `data_pengujian_*.csv`
- Rencana pengujian: `rencana_pengujian.md`

## Model Routing Spesifik VNetra-Lite

### `app/src/` — Kotlin App Lite
- Model **flash** → UI/Compose tweaks, tambah screen baru
- Model **pro** → integrasi sensor fusion, adaptive threshold algorithm

### `analysis/` — Data Analysis
- Model **pro** → analisis CSV pengujian, kalkulasi metrik, visualisasi
- Subagent: `data-analyst-agent`

### `data_pengujian_*.csv` — Raw Test Data
- READ-ONLY untuk agent: file ini tidak boleh dimodifikasi, hanya dibaca
- Analisis output ditulis ke `analysis/` bukan ke file sumber

### `firmware-vnetra/` — ESP32 Firmware Lite
- Sama seperti VNetra: sandbox mode, no auto-flash

## Catatan Khusus VNetra-Lite
Ini versi lightweight yang fokus pada:
- Adaptive threshold berbasis user behavior (file: Flowchart Adaptive Threshold*.md di root)
- Pengujian performa dengan session logger
- Graphify knowledge graph sudah ada di `graphify-out/` — gunakan sebagai referensi arsitektur
