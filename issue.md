---
title: "Refactor: Move EWMA & Kinematics from Android to ESP32 (Edge Computing)"
body:
  - type: textarea
    id: description
    attributes:
      label: Description
      description: What is the problem?
      value: |
        Currently, critical mathematical calculations including the Double EWMA filter (for Distance and Velocity), Time-to-Collision (TTC) Dynamic Threshold, and Momentum Buffer are implemented in the Android application (`NavigationCoordinator.kt`).
        
        This architecture poses a severe failsafe risk:
        1. If the UDP/Wi-Fi connection drops, the `dt` variable spikes, causing velocity derivatives to explode (False Alarms or Missed Detections).
        2. The ESP32 (Dual-Core 240MHz) is significantly underutilized, while the Android app is over-engineered.
        3. Failsafe mode is impossible if the phone dies, as the smart stick becomes "dumb".

        To adhere to strict Doubt-Driven Development and Edge Computing principles, all filtering and kinematics mathematics MUST live in the ESP32 firmware (`firmware-vnetra.ino`). Android should only serve as a dumb TTS speaker.
  - type: checkboxes
    id: tasks
    attributes:
      label: Tasks
      options:
        - label: Migrate Distance EWMA and Velocity EWMA algorithms to C++ in `firmware-vnetra.ino`.
        - label: Migrate Kinematic Momentum and Dynamic Threshold (T) logic to C++ in `firmware-vnetra.ino`.
        - label: Add a 3rd EWMA specifically for Linear Acceleration before Momentum Buffer calculation.
        - label: Refactor Android `NavigationCoordinator.kt` to only receive simple alert states (`DANGER`, `WARNING`, `SAFE`) instead of doing raw physics math.
        - label: Implement hardware Buzzer fallback in ESP32 if UDP Heartbeat drops.
