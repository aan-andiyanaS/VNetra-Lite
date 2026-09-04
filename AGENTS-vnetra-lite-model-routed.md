# VNetra-Lite — AI Agent Workflow and Explicit Gemini Routing

## Project Mission

Build assistive technology for visually impaired users by combining an ESP32-CAM, time-of-flight sensing, Android real-time processing, spatial feedback, and Indonesian audio alerts.

**Engineering targets**:

- End-to-end sensor-to-alert latency below 150 ms.
- BLE packet loss below 1% under the defined test scenario.
- Wi-Fi frame drop below 5% under the defined test scenario.
- Clear, safe, localized TTS alerts.
- Reproducible evidence for thesis evaluation.

These are targets, not claims of achieved performance. Agents must verify all values with current code and measurements.

## System Architecture

```text
[ESP32-CAM + ToF sensor]
        │
        ├── BLE distance packets ──> Android BLE client
        │                            ├── validation and parsing
        │                            ├── ToF-grid state
        │                            └── spatial/alert consumers
        │
        └── Wi-Fi MJPEG stream ───> Android streaming service
                                     ├── HTTP connection
                                     ├── frame extraction/decoding
                                     └── preview/analysis pipeline

[Android application]
        │
        ├── activity/navigation lifecycle
        ├── streaming and device-configuration UI
        ├── ToF-grid rendering
        ├── spatial mapping and orientation processing
        ├── TTS alert scheduling
        ├── session lifecycle management
        └── latency/reliability instrumentation
```

Agents must inspect the current implementation before treating any component map as authoritative.

## Component Contracts

### BLE distance transport

The Android BLE client and ESP32 BLE server form one shared contract.

Current observed packet convention:

```text
[0xAA, 0x55][64 distance bytes][1 XOR checksum]
```

Before changing it, the integration lead must define versioning, compatibility behavior, validation, negative tests, and a physical-device test plan.

### Wi-Fi video transport

The firmware serves an MJPEG stream; Android consumes and displays it. Agents may improve internals, buffering, decoding, reconnection, or rendering, but must preserve interoperability with the deployed firmware or provide a safe migration path.

### ToF and spatial processing

Distance grids, visualization, orientation, quaternion operations, and EKF/sensor-fusion calculations are correctness-sensitive. Any mathematical, coordinate-frame, or calibration change requires Gemini 3.1 Pro High, tests or numerical validation, and an architecture decision record if it changes the model or data interpretation.

### TTS and accessibility

Audio alerts must use Indonesian where configured, prevent alert flooding, honor user volume limits, degrade gracefully if TTS fails, and retain accessible UI behavior.

### Latency instrumentation

Latency logging must remain non-blocking and preserve enough timing evidence to calculate end-to-end distributions. A performance conclusion is invalid without a documented scenario, sample count, before/after results, and limitations.

## Antigravity Gemini Model Routing

Use the exact available Gemini labels:

```text
Gemini 3.5 Flash — Medium
Gemini 3.6 Flash — Medium
Gemini 3.7 Flash — Medium
Gemini 3.1 Pro — High
```

### Android work

| Android task | Gemini model | Notes |
|---|---|---|
| Source-tree inspection, Gradle output summary, routine formatting, local documentation | Gemini 3.5 Flash — Medium | No architecture changes |
| Bounded UI update, known localized bug, unit-test scaffold, simple resource or permission adjustment | Gemini 3.6 Flash — Medium | Keep scope narrow and test locally |
| Multi-file work within one Android subsystem, careful BLE client debugging, robust test additions, local refactor | Gemini 3.7 Flash — Medium | Use isolated branch/worktree |
| Lifecycle race, coroutine/threading failure, CameraX/streaming architecture, cross-module API design, final Android review | Gemini 3.1 Pro — High | Requires evidence and integration checks |

### Firmware work

| Firmware task | Gemini model | Notes |
|---|---|---|
| Source discovery, build-log summary, non-invasive configuration check | Gemini 3.5 Flash — Medium | Read/build only |
| Bounded known sensor/BLE adjustment, routine diagnostics, local code cleanup | Gemini 3.6 Flash — Medium | Sandbox only; no physical flash |
| Multi-file firmware work, careful buffer/polling/reconnect changes, embedded test scaffolding | Gemini 3.7 Flash — Medium | Sandbox only; verify memory/timing |
| FreeRTOS/task architecture, watchdog/reset, heap corruption, protocol changes, camera memory design, safety review | Gemini 3.1 Pro — High | Human approval still required for flashing |

### Performance and integration work

| Task | Gemini model | Notes |
|---|---|---|
| Parse logs, CSV files, ADB/serial summaries, baseline table preparation | Gemini 3.5 Flash — Medium | Evidence preparation only |
| Add bounded metrics/instrumentation, analyze one subsystem | Gemini 3.7 Flash — Medium | Do not make cross-layer conclusions alone |
| End-to-end bottleneck analysis, latency experiment design, statistical conclusion, protocol/client-server integration, final release gate | Gemini 3.1 Pro — High | Requires reproducible evidence |

## Subagent Responsibilities

### Android implementation subagent

**Default model**: Gemini 3.6 Flash — Medium

**Upgrade to**: Gemini 3.7 Flash — Medium for bounded multi-file subsystem work; Gemini 3.1 Pro — High for lifecycle/concurrency/architecture.

**Scope**:

- Kotlin application behavior, UI, CameraX, BLE client, streaming client, TTS, lifecycle, tests, accessibility.
- May introduce new packages/classes or refactor existing ones when it improves maintainability or correctness.
- Must inspect source, interfaces, tests, and runtime constraints before editing.

**Deliverables**:

- Focused branch/worktree.
- Tests and build results.
- Accessibility and lifecycle impact.
- Protocol assumptions.
- Handoff note with risks and next action.

### Firmware implementation subagent

**Default model**: Gemini 3.6 Flash — Medium

**Upgrade to**: Gemini 3.7 Flash — Medium for bounded multi-file firmware work; Gemini 3.1 Pro — High for timing, memory, FreeRTOS, protocol, or safety work.

**Scope**:

- ESP32 sensor acquisition, BLE server, Wi-Fi/MJPEG, camera buffers, timing, watchdog/recovery, diagnostics.
- May refactor firmware design when justified by memory, timing, safety, or maintainability evidence.

**Hard constraints**:

- All automated execution remains sandboxed.
- No `platformio run --target upload`, OTA deploy, bootloader action, or device-changing action without explicit human approval.
- Validate heap, stack, timing, packet framing, and reconnect behavior after material changes.

### Performance and reliability subagent

**Default model**: Gemini 3.5 Flash — Medium for collection and summarization.

**Upgrade to**: Gemini 3.1 Pro — High for experiment design, bottleneck analysis, and performance conclusions.

**Scope**:

- Timing instrumentation, data collection, packet loss/frame drop tracking, metrics aggregation, and reliability analysis.
- Must measure before proposing optimization.
- Must separate observation from hypothesis and hypothesis from verified conclusion.

### Integration lead subagent

**Model**: Gemini 3.1 Pro — High

**Scope**:

- Owns shared protocol/API/schema changes.
- Reviews Android and firmware boundary compatibility.
- Resolves branch conflicts and incompatible assumptions.
- Enforces integration, physical-device, and regression gates.

## Parallel Work Standard

### Allowed parallel pattern

```text
Worktree A — Android implementation
Model: Gemini 3.6 Flash Medium or Gemini 3.7 Flash Medium

Worktree B — Firmware implementation
Model: Gemini 3.6 Flash Medium or Gemini 3.7 Flash Medium

Worktree C — Metrics and evidence
Model: Gemini 3.5 Flash Medium for analysis preparation
       Gemini 3.1 Pro High for conclusions

Worktree D — Integration lead
Model: Gemini 3.1 Pro High
Runs after implementation branches are ready
```

### Rules

1. Every editing agent uses a dedicated branch/worktree.
2. One owner edits every shared BLE packet definition, schema, build configuration, manifest, central data type, or public API.
3. Other agents may investigate or prepare proposals but must not concurrently modify the owned contract.
4. Do not split tightly coupled work merely to maximize agent count.
5. The integration lead verifies the changed protocol or interface on both Android and ESP32 sides.
6. Physical hardware validation is mandatory when behavior depends on BLE, camera, Wi-Fi, ToF, timing, or audio output.
7. No agent may flash physical hardware without explicit human approval.

## Repository Discovery Procedure

```bash
pwd
git status --short
git log -8 --oneline
find . -maxdepth 5 -type f | sort
find app -type f \( -name '*.kt' -o -name '*.java' -o -name '*.xml' -o -name '*.gradle*' \) | sort
find firmware-vnetra -type f \( -name '*.ino' -o -name '*.cpp' -o -name '*.h' -o -name 'platformio.ini' \) | sort
```

Before a material change, inspect:

- Current entry points, services/managers, and data models.
- Build and dependency configuration.
- Relevant tests.
- Protocol and parsing code on both sides of the transport boundary.
- Recent commits and uncommitted work.

## Build and Test Workflow

### Android

```bash
./gradlew assembleDebug
./gradlew test
./gradlew connectedAndroidTest
./gradlew lint
```

### Firmware

```bash
cd firmware-vnetra/firmware-vnetra
platformio run
platformio device monitor
# platformio run --target upload  # Explicit human approval required
```

### Performance evidence

A valid performance report must include:

- Firmware commit, Android commit, device models, and build variants.
- Test scenario, network conditions, duration, and sample count.
- Mean, median, p95, p99, jitter, BLE loss, and frame drop where applicable.
- Before/after comparison.
- Known confounders, failures, and limitations.

## Obsidian Durable Memory

### Required vault structure

```text
ObsidianVault-VNetra/
├── 00-Inbox/
│   └── session-bridge-YYYY-MM-DD.md
├── 01-Daily/
│   └── YYYY-MM-DD.md
├── 02-Projects/
│   ├── android.md
│   ├── firmware.md
│   ├── transport-contracts.md
│   └── performance.md
├── 03-Entities/
│   ├── file-structure.md
│   ├── Android.md
│   ├── ESP32.md
│   └── sensors-and-protocols.md
├── 04-Skills/
│   ├── testing.md
│   ├── embedded-safety.md
│   └── performance-measurement.md
├── 05-Architecture/
│   ├── CONTEXT.md
│   └── decisions/
└── 99-Archive/
```

### Session start

```yaml
read:
  - 05-Architecture/CONTEXT.md
  - latest relevant session bridge
  - task-relevant component notes and ADRs
inspect:
  - git status
  - recent commits
  - current source tree
  - relevant source and test files
```

### During work

```yaml
rules:
  - Search notes instead of loading all historical context.
  - Load source code only on demand.
  - Append verified progress and measurements to the daily note.
  - Record architecture/protocol/safety decisions as ADRs.
  - Keep raw logs and CSV files outside summary notes; link them instead.
```

### Session end

```yaml
write:
  - relevant component status, evidence, risk, and next step
  - daily summary
  - session bridge under 500 words
  - ADR when the change is architectural or cross-layer
update:
  - CONTEXT.md only when architecture/system boundaries changed
```

### Context budget

- `05-Architecture/CONTEXT.md`: under approximately 1,200 words.
- Session bridge: under approximately 500 words.
- Component notes: current status and links, not source-code dumps.
- Daily notes: append-only; archive monthly.
- Raw artifacts: retain in project storage and link from Obsidian.

Obsidian preserves only explicitly written engineering context. It does not automatically store an agent's hidden chat history.

## Automatic Structure Update

Update `03-Entities/file-structure.md` whenever modules, services, drivers, build targets, dependencies, protocols, schemas, or public interfaces are added, removed, renamed, moved, or materially changed.

```bash
find . -type f \
  \( -name '*.kt' -o -name '*.java' -o -name '*.xml' -o -name '*.ino' -o \
     -name '*.cpp' -o -name '*.h' -o -name '*.gradle*' -o -name 'platformio.ini' \) \
  -not -path './.git/*' | sort
```

Required sequence:

1. Compare the new snapshot with the stored structure note.
2. Record timestamp, current commit, and concise path-level changes.
3. Update `CONTEXT.md` if a boundary changed.
4. Create an ADR if the change modifies architecture, protocol, concurrency, or persistence.
5. Add one short line to the daily note.

Perform this check at session start, after structural commits, and in weekly review.

## RTK Guidance

Use RTK to compress repetitive terminal output where compatible.

```toml
[commands.gradle]
enabled = true
compression = "aggressive"

[commands.platformio]
enabled = true
compression = "aggressive"

[commands.adb]
enabled = true
compression = "moderate"

[commands.git]
enabled = true
compression = "aggressive"
```

Do not over-compress evidence for thesis experiments, hardware faults, security incidents, or failing tests until the raw output is retained.

## Session Bridge Template

```markdown
# VNetra-Lite Session Bridge — YYYY-MM-DD HH:mm

## Active Work
- Branch/worktree:
- Subagent role:
- Gemini model:
- Why this model was selected:

## Objective
-

## Completed
- [ ]

## Verified Evidence
- Build/test:
- Hardware test:
- Metrics:
- Commit/diff:

## Risks and Blockers
-

## Next Action
-

## Structure or Contract Changes
-
```

## Completion Gate

Before completion, verify:

- Current code, tests, Git state, and relevant interfaces were inspected.
- The selected Gemini model matched task risk and complexity.
- Relevant builds/tests pass.
- Physical-device tests were performed where needed.
- Shared contract changes were validated on Android and ESP32.
- Performance claims include reproducible measurements.
- No irreversible action occurred without human approval.
- Obsidian has a concise, verified handoff for the next session.
