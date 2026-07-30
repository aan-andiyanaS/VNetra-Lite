# Graph Report - E:\Project\Skripsi\VNetra-Lite  (2026-07-30)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 270 nodes · 423 edges · 16 communities (13 shown, 3 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- StreamService
- DeviceConfigActivity
- StreamActivity
- MainActivity
- BleManager
- TtsAlertManager
- SpatialMappingUtils
- NavigationCoordinator
- ToFGridRenderer
- LatencyLogger
- gradlew
- ExampleInstrumentedTest
- ExampleUnitTest

## God Nodes (most connected - your core abstractions)
1. `StreamService` - 42 edges
2. `StreamActivity` - 35 edges
3. `BleManager` - 25 edges
4. `DeviceConfigActivity` - 23 edges
5. `MainActivity` - 17 edges
6. `TtsAlertManager` - 17 edges
7. `SessionManager` - 15 edges
8. `DeviceAdapter` - 11 edges
9. `WifiAdapter` - 11 edges
10. `NavigationCoordinator` - 11 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `BleManager`  [EXTRACTED]
  app/src/main/java/com/airi/vnetra/MainActivity.kt → app/src/main/java/com/airi/vnetra/ble/BleManager.kt
- `DeviceConfigActivity` --references--> `BleManager`  [EXTRACTED]
  app/src/main/java/com/airi/vnetra/ui/DeviceConfigActivity.kt → app/src/main/java/com/airi/vnetra/ble/BleManager.kt
- `StreamService` --references--> `LatencyLogger`  [EXTRACTED]
  app/src/main/java/com/airi/vnetra/service/StreamService.kt → app/src/main/java/com/airi/vnetra/util/LatencyLogger.kt
- `StreamService` --references--> `NavigationCoordinator`  [EXTRACTED]
  app/src/main/java/com/airi/vnetra/service/StreamService.kt → app/src/main/java/com/airi/vnetra/util/NavigationCoordinator.kt
- `StreamService` --references--> `TtsAlertManager`  [EXTRACTED]
  app/src/main/java/com/airi/vnetra/service/StreamService.kt → app/src/main/java/com/airi/vnetra/util/TtsAlertManager.kt

## Import Cycles
- None detected.

## Communities (16 total, 3 thin omitted)

### Community 0 - "StreamService"
Cohesion: 0.07
Nodes (26): ConnectionState, CONNECTED, CONNECTING, DISCONNECTED, createStartIntent(), createStopIntent(), Context, FloatArray (+18 more)

### Community 1 - "DeviceConfigActivity"
Cohesion: 0.08
Nodes (18): ActivityDeviceConfigBinding, AlertDialog, fromString(), SignalStrength, EXCELLENT, FAIR, GOOD, WEAK (+10 more)

### Community 2 - "StreamActivity"
Cohesion: 0.08
Nodes (15): ActivityStreamBinding, CONNECTING, createIntent(), ERROR, AppCompatActivity, Bundle, Context, FloatArray (+7 more)

### Community 3 - "MainActivity"
Cohesion: 0.08
Nodes (13): ActivityMainBinding, DeviceAdapter, AppCompatActivity, Bundle, RecyclerView, ScanResult, ViewGroup, ViewHolder (+5 more)

### Community 4 - "BleManager"
Cohesion: 0.08
Nodes (16): BleManager, ConnectionState, CONNECTED, CONNECTING, DISCONNECTED, DISCOVERING_SERVICES, READY, ScanResult (+8 more)

### Community 5 - "TtsAlertManager"
Cohesion: 0.18
Nodes (5): TtsAlertManager, TtsMessage, AudioAttributes, AudioTrack, TextToSpeech

### Community 6 - "SpatialMappingUtils"
Cohesion: 0.26
Nodes (5): Cell, FloatArray, IntArray, ObstacleAnalysis, SpatialMappingUtils

### Community 7 - "NavigationCoordinator"
Cohesion: 0.33
Nodes (3): FloatArray, NavigationCoordinator, ObstaclePhysics

### Community 8 - "ToFGridRenderer"
Cohesion: 0.27
Nodes (4): FloatArray, IntArray, ToFGridRenderer, TextView

### Community 10 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **15 isolated node(s):** `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `DISCOVERING_SERVICES`, `READY` (+10 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **3 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `StreamActivity` connect `StreamActivity` to `StreamService`, `ToFGridRenderer`, `MainActivity`?**
  _High betweenness centrality (0.527) - this node is a cross-community bridge._
- **Why does `StreamService` connect `StreamService` to `LatencyLogger`, `StreamActivity`, `TtsAlertManager`, `NavigationCoordinator`?**
  _High betweenness centrality (0.459) - this node is a cross-community bridge._
- **Why does `SessionManager` connect `MainActivity` to `DeviceConfigActivity`, `StreamActivity`?**
  _High betweenness centrality (0.445) - this node is a cross-community bridge._
- **What connects `DISCONNECTED`, `CONNECTING`, `CONNECTED` to the rest of the system?**
  _15 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `StreamService` be split into smaller, more focused modules?**
  _Cohesion score 0.06588235294117648 - nodes in this community are weakly interconnected._
- **Should `DeviceConfigActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.08292682926829269 - nodes in this community are weakly interconnected._
- **Should `StreamActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.08414634146341464 - nodes in this community are weakly interconnected._