# Graph Report - e:/Project/Skripsi/VNetra-Lite  (2026-08-03)

## Corpus Check
- 45 files · ~71,525 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 324 nodes · 545 edges · 18 communities (15 shown, 3 thin omitted)
- Extraction: 100% EXTRACTED · 0% INFERRED · 0% AMBIGUOUS
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Android Stream Service dan State
- ESP32 Firmware dan Sensor Tasks
- WiFi Model dan Signal Strength
- Stream Activity UI
- MainActivity dan BLE Scanner
- BLE Manager
- TTS Alert Manager
- Navigation dan Obstacle Physics
- Spatial Mapping ToF EWMA
- ToF Grid Renderer
- Latency Logger
- BLE Characteristic Callbacks
- Gradle Build Scripts
- Android Instrumented Tests
- Unit Tests

## God Nodes (most connected - your core abstractions)
1. `StreamService` - 42 edges
2. `StreamActivity` - 35 edges
3. `BleManager` - 25 edges
4. `DeviceConfigActivity` - 23 edges
5. `MainActivity` - 17 edges
6. `TtsAlertManager` - 17 edges
7. `SessionManager` - 15 edges
8. `NavigationCoordinator` - 13 edges
9. `handleButton()` - 13 edges
10. `DeviceAdapter` - 11 edges

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

## Communities (18 total, 3 thin omitted)

### Community 0 - "Android Stream Service dan State"
Cohesion: 0.07
Nodes (26): ConnectionState, CONNECTED, CONNECTING, DISCONNECTED, createStartIntent(), createStopIntent(), Context, FloatArray (+18 more)

### Community 1 - "ESP32 Firmware dan Sensor Tasks"
Cohesion: 0.10
Nodes (42): AsyncWebSocket, AsyncWebSocketClient, AwsEventType, BLEServer, BLEServerCallbacks, bleConnectWifi(), calibrateAccelBias(), clearWiFiCredentials() (+34 more)

### Community 2 - "WiFi Model dan Signal Strength"
Cohesion: 0.08
Nodes (18): ActivityDeviceConfigBinding, AlertDialog, fromString(), SignalStrength, EXCELLENT, FAIR, GOOD, WEAK (+10 more)

### Community 3 - "Stream Activity UI"
Cohesion: 0.08
Nodes (15): ActivityStreamBinding, CONNECTING, createIntent(), ERROR, AppCompatActivity, Bundle, Context, FloatArray (+7 more)

### Community 4 - "MainActivity dan BLE Scanner"
Cohesion: 0.08
Nodes (13): ActivityMainBinding, DeviceAdapter, AppCompatActivity, Bundle, RecyclerView, ScanResult, ViewGroup, ViewHolder (+5 more)

### Community 5 - "BLE Manager"
Cohesion: 0.08
Nodes (16): BleManager, ConnectionState, CONNECTED, CONNECTING, DISCONNECTED, DISCOVERING_SERVICES, READY, ScanResult (+8 more)

### Community 6 - "TTS Alert Manager"
Cohesion: 0.18
Nodes (5): TtsAlertManager, TtsMessage, AudioAttributes, AudioTrack, TextToSpeech

### Community 7 - "Navigation dan Obstacle Physics"
Cohesion: 0.29
Nodes (3): FloatArray, NavigationCoordinator, ObstaclePhysics

### Community 8 - "Spatial Mapping ToF EWMA"
Cohesion: 0.23
Nodes (5): Cell, FloatArray, IntArray, ObstacleAnalysis, SpatialMappingUtils

### Community 9 - "ToF Grid Renderer"
Cohesion: 0.27
Nodes (4): FloatArray, IntArray, ToFGridRenderer, TextView

### Community 11 - "BLE Characteristic Callbacks"
Cohesion: 0.50
Nodes (3): BLECharacteristic, BLECharacteristicCallbacks, CommandCallbacks

### Community 12 - "Gradle Build Scripts"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **15 isolated node(s):** `DISCONNECTED`, `CONNECTING`, `CONNECTED`, `DISCOVERING_SERVICES`, `READY` (+10 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **3 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `StreamActivity` connect `Stream Activity UI` to `Android Stream Service dan State`, `ToF Grid Renderer`, `MainActivity dan BLE Scanner`?**
  _High betweenness centrality (0.371) - this node is a cross-community bridge._
- **Why does `StreamService` connect `Android Stream Service dan State` to `Latency Logger`, `Stream Activity UI`, `TTS Alert Manager`, `Navigation dan Obstacle Physics`?**
  _High betweenness centrality (0.327) - this node is a cross-community bridge._
- **Why does `SessionManager` connect `MainActivity dan BLE Scanner` to `WiFi Model dan Signal Strength`, `Stream Activity UI`?**
  _High betweenness centrality (0.312) - this node is a cross-community bridge._
- **What connects `DISCONNECTED`, `CONNECTING`, `CONNECTED` to the rest of the system?**
  _15 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Android Stream Service dan State` be split into smaller, more focused modules?**
  _Cohesion score 0.06509803921568627 - nodes in this community are weakly interconnected._
- **Should `ESP32 Firmware dan Sensor Tasks` be split into smaller, more focused modules?**
  _Cohesion score 0.10453283996299723 - nodes in this community are weakly interconnected._
- **Should `WiFi Model dan Signal Strength` be split into smaller, more focused modules?**
  _Cohesion score 0.08292682926829269 - nodes in this community are weakly interconnected._