# Title: Fix TTS Delay, Spam, Threshold, and IMU Jitter

## Description
This issue addresses four major bugs identified during a deep code audit:

1. **TTS Delay**: IMU UDP rate was capped at 50ms, causing latency in spatial mapping and TTS.
2. **TTS Spam (Post-Rotation)**: TTS triggers spam alerts immediately after the user stops rotating their head because `isSameSemanticState` instantly becomes false without any cooldown.
3. **Threshold Stuck at 1200mm**: Moving toward static walls did not scale the threshold dynamically because `isStaticObject` over-filtered valid linear acceleration values (`vRaw` forced to 0).
4. **IMU Jitter**: The `a_lin_mag_raw` experienced large spikes when the Mahony filter quaternion was not yet converged or during rapid rotations, leading to false positive acceleration. ToF sentinel values (-1) were also incorrectly bitmasked to 65535 in Kotlin, masking invalid range values.

## Proposed Changes (Ponytail & Clean Code applied)
- **Firmware**: Increased IMU send tick from 50ms to 25ms. Added an outlier clamp `> 20.0f` and an adaptive EMA alpha for linear acceleration smoothing. Increased the noise gate to 0.3f.
- **Android - NavigationCoordinator**: Removed the `isStaticObject && aLin < 2.94f` filter. Added `headRotationStopTimeMs` to track rotation state changes.
- **Android - TtsAlertManager**: Implemented a 500ms post-rotation cooldown in `process()` to prevent TTS spam.
- **Android - StreamService & SpatialMappingUtils**: Fixed ToF sentinel bitwise masking. Handled sentinel (`< 0`) correctly in `extractCloseCells` to prevent EMA pollution. Reset `SpatialMappingUtils` state upon reconnect to prevent state leakage.

## Steps to Reproduce
1. Walk towards a static wall at a normal pace -> warning threshold remained at 1200mm.
2. Turn head quickly while standing near an object -> TTS spam triggered immediately upon stopping.
3. Stand still -> Acceleration spikes triggered false moving detections.

## Expected Behavior
- Post-rotation should gracefully ignore changes for 500ms to allow semantic memory settling.
- Walking towards a wall should scale the threshold up dynamically based on velocity.
- Acceleration should be smooth even during rotation or startup.
- ToF sentinels should correctly represent out-of-range or invalid data without polluting the EMA.

## Fixes Implemented
- [x] Post-rotation cooldown added
- [x] Threshold static filter removed
- [x] ToF sentinel bitmask fixed
- [x] Acceleration EMA adaptive alpha & clamp applied
- [x] UDP rate increased to 40Hz
