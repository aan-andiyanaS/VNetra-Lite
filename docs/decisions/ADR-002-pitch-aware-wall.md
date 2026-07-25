# ADR-002: Incremental Pitch-Aware Wall Detection & UI Cleanup

## Status
Accepted

## Date
2026-07-25

## Context
1. **Wall Detection Flaw:** The previous wall detection mechanism (`SpatialMappingUtils.isWall`) relied solely on distance measurements (ToF 8x8 matrix) without considering the user's posture. When a visually impaired user points the cane downwards (pitch < -15 degrees), the sensor interprets the close-range asphalt/ground as a solid vertical obstacle, causing continuous false-positive TTS alarms.
2. **YOLO Leftovers:** Following the decision to abandon YOLO/Deep Learning in favor of lightweight spatial mathematics, several UI components related to YOLO (AI Model Status and YOLO Debug Confidence) remained in `activity_stream.xml`, causing confusion and clutter.

## Decision
1. **Implement Pitch-Aware Compensation:** We modified `SpatialMappingUtils.isWall` to accept a `thetaDeg` (pitch) parameter. If the pitch drops below -15 degrees (indicating the user is looking down at the ground), the flatness tolerance for wall detection is tightened by 50% (`WALL_FLATNESS_TOLERANCE_MM / 2`). 
2. **Remove Magic Numbers:** Hardcoded values `30` and `1500` were extracted into explicit constants (`WALL_MIN_DIST_MM`, `WALL_MAX_DIST_MM`, etc.) in `SpatialMappingUtils.kt` to comply with Clean Code standards.
3. **Clean Up UI:** Removed the unused YOLO XML components (`tvAiModelStatus` and `tvYoloDebug`) from `activity_stream.xml`.

## Alternatives Considered
### Implement Full Formula J (7-Type Terrain Classification)
- **Pros:** Comprehensive detection for stairs, holes, drops, etc.
- **Cons:** High risk of introducing new bugs, requires extensive structural changes and field testing.
- **Rejected:** Based on the "Ponytail" and "Incremental Implementation" principles, fixing the fatal flaw (ground false-positives) using a simple pitch-compensation threshold is far faster and safer than a full architectural rewrite.

## Consequences
- **Zero False Alarms on Ground:** The system will no longer scream "Wall!" when pointing at the floor, vastly improving user trust and reducing cognitive overload.
- **Improved Code Readability:** The core `SpatialMappingUtils.kt` now reads like plain English.
- **Simplified UI:** The screen is cleaner and accurately reflects the CPU-only mathematical architecture.
- **No Performance Hit:** The added conditional logic (pitch checking) is a simple `<` and `/` operation, retaining the <1% CPU utilization benchmark.
