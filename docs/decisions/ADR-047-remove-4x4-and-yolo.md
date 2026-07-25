# ADR-047: Removal of 4x4 ToF Mode and Deprecated YOLO Components

## Status
Accepted

## Date
2026-07-26

## Context
The VNetra-Lite system previously supported two modes for its Time-of-Flight (ToF) sensor matrix: 8x8 (default) and 4x4 (for higher SNR). Additionally, there were leftover UI elements and code paths originally designed for a YOLO/Deep Learning model running on a companion Android smartphone. 

Recent evaluations determined that:
1. The 8x8 resolution is completely sufficient and stable for our use case.
2. The complexity of maintaining dual resolutions (4x4 and 8x8) across both the Android app (StreamActivity.kt, StreamService.kt) and the ESP32 firmware (firmware-vnetra.ino) violates YAGNI (You Aren't Gonna Need It) principles and introduces unnecessary bugs and UI clutter.
3. The YOLO components are entirely obsolete because the obstacle detection now runs safely and efficiently using purely spatial mapping based on raw UDP ToF and IMU data on the CPU. The smartphone's CPU alone is highly capable of processing this spatial logic without complex ML models.

## Decision
1. **Remove 4x4 ToF Mode completely:** Lock the entire system architecture (App and Firmware) to strictly use the 8x8 ToF mode.
2. **Remove YOLO UI elements:** Purge all leftover visual components and variables related to AI/YOLO modes from the application UI.
3. **Refactor spatial logic:** Hardcode all grid calculations (e.g., in SpatialMappingUtils.kt and TofDepthEstimator.kt) to assume an 8x8 grid (64 items) instead of dynamically checking the resolution.

## Alternatives Considered

### Keeping 4x4 Mode for Edge Cases
- **Pros:** Might provide slightly better Signal-to-Noise Ratio (SNR) in highly noisy environments.
- **Cons:** Requires complex state management, reconnection logic, and dynamic grid rendering, which has historically caused crashes and UI synchronization issues.
- **Rejected:** The 8x8 mode already performs excellently. Maintaining 4x4 is not worth the architectural overhead.

### Just Hiding the UI Buttons (Leaving the logic intact)
- **Pros:** Minimal code changes, easy to revert.
- **Cons:** Leaves dead code ("God Class" bloat) in StreamActivity and StreamService, confusing future developers and increasing the app's complexity.
- **Rejected:** Violates Clean Code guidelines. Dead code must be removed.

## Consequences
- The Android application codebase is significantly lighter and easier to maintain.
- StreamActivity.kt is no longer responsible for switching modes or maintaining UI state for ToF resolutions, drastically simplifying its logic.
- Firmware is simplified as it no longer needs to listen for or process mode-switch commands over WebSocket.
- The system is now permanently optimized for the 8x8 matrix layout.
