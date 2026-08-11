# Graph Report - e:\Project\Skripsi\VNetra-Lite  (2026-08-05)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 280 nodes · 421 edges · 26 communities (14 shown, 12 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS · INFERRED: 1 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `95af7bfc`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- StreamService
- DeviceConfigActivity
- StreamActivity
- MainActivity
- BleManager
- TtsAlertManager
- NavigationCoordinator
- .onCreate
- SpatialMappingUtils
- ToFGridRenderer
- gradlew
- ExampleInstrumentedTest
- ExampleUnitTest
- java
- AsyncWebSocket
- AsyncWebSocketClient
- AwsEventType
- BLECharacteristic
- BLECharacteristicCallbacks
- BLEServer
- BLEServerCallbacks
- sensors_event_t
- String

## God Nodes (most connected - your core abstractions)
1. `StreamService` - 42 edges
2. `StreamActivity` - 35 edges
3. `BleManager` - 25 edges
4. `DeviceConfigActivity` - 23 edges
5. `MainActivity` - 17 edges
6. `TtsAlertManager` - 17 edges
7. `SessionManager` - 15 edges
8. `NavigationCoordinator` - 13 edges
9. `DeviceAdapter` - 11 edges
10. `WifiAdapter` - 11 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `BleManager`  [EXTRACTED]
  app/src/main/java/com/airi/vnetra/MainActivity.kt → app/src/main/java/com/airi/vnetra/ble/BleManager.kt
- `DeviceConfigActivity` --references--> `BleManager`  [EXTRACTED]
  app/src/main/java/com/airi/vnetra/ui/DeviceConfigActivity.kt → app/src/main/java/com/airi/vnetra/ble/BleManager.kt
- `StreamService` --references--> `NavigationCoordinator`  [EXTRACTED]
  app/src/main/java/com/airi/vnetra/service/StreamService.kt → app/src/main/java/com/airi/vnetra/util/NavigationCoordinator.kt
- `StreamService` --references--> `LatencyMetrics`  [EXTRACTED]
  app/src/main/java/com/airi/vnetra/service/StreamService.kt → app/src/main/java/com/airi/vnetra/util/SessionDataLogger.kt
- `StreamService` --references--> `SessionDataLogger`  [EXTRACTED]
  app/src/main/java/com/airi/vnetra/service/StreamService.kt → app/src/main/java/com/airi/vnetra/util/SessionDataLogger.kt

## Import Cycles
- None detected.

## Communities (26 total, 12 thin omitted)

### Community 0 - "StreamService"
Cohesion: 0.07
Nodes (25): ConnectionState, CONNECTED, CONNECTING, DISCONNECTED, createStartIntent(), createStopIntent(), Context, FloatArray (+17 more)

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

### Community 6 - "NavigationCoordinator"
Cohesion: 0.29
Nodes (3): FloatArray, NavigationCoordinator, ObstaclePhysics

### Community 7 - ".onCreate"
Cohesion: 0.25
Nodes (4): java, LatencyMetrics, SessionDataLogger, SessionFrame

### Community 8 - "SpatialMappingUtils"
Cohesion: 0.24
Nodes (4): FloatArray, IntArray, ObstacleAnalysis, SpatialMappingUtils

### Community 9 - "ToFGridRenderer"
Cohesion: 0.27
Nodes (4): FloatArray, IntArray, ToFGridRenderer, TextView

### Community 10 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **15 isolated node(s):** `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `DISCOVERING_SERVICES`, `READY` (+10 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **12 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `StreamActivity` connect `StreamActivity` to `StreamService`, `ToFGridRenderer`, `MainActivity`?**
  _High betweenness centrality (0.493) - this node is a cross-community bridge._
- **Why does `StreamService` connect `StreamService` to `StreamActivity`, `TtsAlertManager`, `NavigationCoordinator`, `.onCreate`?**
  _High betweenness centrality (0.432) - this node is a cross-community bridge._
- **Why does `SessionManager` connect `MainActivity` to `DeviceConfigActivity`, `StreamActivity`?**
  _High betweenness centrality (0.416) - this node is a cross-community bridge._
- **What connects `DISCONNECTED`, `CONNECTING`, `CONNECTED` to the rest of the system?**
  _15 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `StreamService` be split into smaller, more focused modules?**
  _Cohesion score 0.07030527289546716 - nodes in this community are weakly interconnected._
- **Should `DeviceConfigActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.08292682926829269 - nodes in this community are weakly interconnected._
- **Should `StreamActivity` be split into smaller, more focused modules?**
  _Cohesion score 0.08414634146341464 - nodes in this community are weakly interconnected._