# Vibe Code Workspace — AI Agent Orchestration

## Mission Statement

Enable senior-level AI agents to autonomously develop, maintain, and optimize multi-project software systems with deep domain expertise, adaptive learning, persistent contextual memory, and explicit model routing.

This workspace-level file defines shared behavior. A project-local `AGENTS.md` may refine these rules for an individual repository and takes precedence inside that repository.

## Portfolio Overview

| Project | Domain | Stack | Status |
|---|---|---|---|
| **VNetra-Lite** | Assistive Technology | Android Kotlin + ESP32 C++ | Active Development |
| **Future Projects** | To be discovered | To be discovered | To be discovered |

## Project Discovery Protocol

Agents MUST discover the project structure dynamically before making implementation assumptions.

```bash
# Inspect repository topology and current state
pwd
find . -maxdepth 4 -type f | sort
git status --short
git log -8 --oneline

# Discover Android, embedded, and build sources
find . -maxdepth 6 -type f \( \
  -name '*.kt' -o -name '*.java' -o -name '*.cpp' -o -name '*.h' -o \
  -name '*.ino' -o -name '*.gradle*' -o -name 'settings.gradle*' -o \
  -name 'platformio.ini' -o -name 'CMakeLists.txt' \
\) | sort

# Identify high-level components
rg -n --glob '*.kt' --glob '*.cpp' --glob '*.h' --glob '*.ino' \
  'class .*Service|class .*Manager|class .*Controller|fun main\(|void setup\(|void loop\('
```

**DO NOT** hard-code file lists as the exclusive scope of an agent. Agents must discover the codebase, inspect interfaces, identify dependencies, and adapt to structural changes.

## Antigravity Model Inventory

Use the exact model labels currently visible in Antigravity:

```text
Gemini 3.5 Flash — Medium
Gemini 3.6 Flash — Medium
Gemini 3.7 Flash — Medium
Gemini 3.1 Pro — High
Claude Sonnet 4.6 — Thinking
Claude Opus 4.6 — Thinking
GPT-OSS 120B — Medium
```

This workflow routes Gemini models explicitly. Do not invent Flash Low/High or Gemini Pro Low modes unless they are visibly available in the current Antigravity model selector.

## Explicit Gemini Model Routing

### Gemini Flash hierarchy

| Model | Role | Best-fit task scope | Avoid for |
|---|---|---|---|
| **Gemini 3.5 Flash — Medium** | Fast utility worker | Repository discovery, file-tree indexing, mechanical checks, log/CSV summarization, formatting, simple documentation updates, test-output triage | Architecture, ambiguous bugs, protocol changes, concurrency, hardware safety decisions |
| **Gemini 3.6 Flash — Medium** | Default bounded implementation worker | Localized Kotlin/C++ fixes, routine UI work, unit-test scaffolding, dependency inspection, simple BLE or sensor changes with known behavior | Cross-layer redesign, lifecycle races, memory corruption, thesis-grade performance conclusions |
| **Gemini 3.7 Flash — Medium** | Strong bounded subsystem worker | Multi-file feature within one subsystem, robust test additions, medium-complexity debugging, localized refactoring, first-pass code review | Final architecture, shared protocol ownership, safety-critical firmware decisions, unresolved concurrency and mathematical validation |
| **Gemini 3.1 Pro — High** | Senior systems and integration engineer | Architecture, BLE/client-server contract changes, concurrency/lifecycle issues, sensor fusion, spatial math, latency optimization, security review, final integration review | Autonomous irreversible actions such as device flashing, deployment, deletion, or merging without approval |

### Task routing matrix

| Task | Primary subagent | Exact model | Parallelization |
|---|---|---|---|
| Source-tree discovery and Git-state check | Discovery worker | Gemini 3.5 Flash — Medium | Yes |
| Build log, `adb logcat`, CSV, or serial-log summarization | Evidence worker | Gemini 3.5 Flash — Medium | Yes |
| Mechanical rename, formatting, import cleanup | Domain worker | Gemini 3.5 Flash — Medium | Yes when write sets are disjoint |
| Local UI adjustment or clear bug fix | Android worker | Gemini 3.6 Flash — Medium | Yes when files are disjoint |
| Bounded ESP32 sensor/BLE adjustment with stable protocol | Embedded worker | Gemini 3.6 Flash — Medium | Yes when files are disjoint |
| Unit tests for established behavior | Android or embedded worker | Gemini 3.6 Flash — Medium | Yes |
| Multi-file change inside Android or firmware subsystem | Domain worker | Gemini 3.7 Flash — Medium | Yes with worktree isolation |
| Medium-complexity BLE/MJPEG debugging | Android or embedded worker | Gemini 3.7 Flash — Medium | Independent analysis may parallelize |
| Initial code review and technical-debt inventory | Review worker | Gemini 3.7 Flash — Medium | Yes |
| Shared BLE packet/protocol/schema change | Integration lead | Gemini 3.1 Pro — High | Proposal analysis only; one writer owns contract |
| Android lifecycle, coroutine, or race-condition debugging | Android architect | Gemini 3.1 Pro — High | Investigation may parallelize |
| ESP32 memory corruption, watchdog, timing, or FreeRTOS design | Embedded architect | Gemini 3.1 Pro — High | Investigation may parallelize |
| EKF, quaternion, coordinate transform, or sensor-fusion validation | Systems analyst | Gemini 3.1 Pro — High | Analysis may parallelize; one owner implements |
| End-to-end latency and reliability optimization | Performance engineer | Gemini 3.1 Pro — High | Measurement analysis may parallelize |
| Final integration review and release-readiness review | Integration lead | Gemini 3.1 Pro — High | Sequential gate |

### Escalation policy

1. Start with the lowest exact Gemini model that can reliably complete the task.
2. Escalate from 3.5 Flash to 3.6 Flash when implementation is required.
3. Escalate from 3.6 Flash to 3.7 Flash when the task spans multiple files in a bounded subsystem or the first debugging attempt fails.
4. Escalate to Gemini 3.1 Pro High when the task crosses components, changes a shared contract, involves concurrency/lifecycle, hardware safety, mathematical correctness, or requires final judgment.
5. A small file can require Pro High if it controls a protocol, thread boundary, memory ownership, or physical hardware.
6. A large repetitive file can remain a Flash task.
7. Record the selected model and reason in the session bridge for material engineering work.

## Shared Senior Subagents

### `android-expert`

**Default model**: Gemini 3.7 Flash — Medium

**Escalation model**: Gemini 3.1 Pro — High

**Use when**: Android Kotlin, Jetpack, CameraX, lifecycle, coroutines, StateFlow, BLE clients, Wi-Fi, media pipelines, TTS, accessibility, mobile performance.

**Senior responsibilities**:

- Discover existing architecture before changing it.
- Validate lifecycle ownership, cancellation, main-thread safety, configuration changes, and process recreation.
- Prefer structured concurrency and explicit state models.
- Keep accessibility functional: semantic labels, focus order, screen-reader behavior, usable touch targets, and safe audio feedback.
- Measure frame pacing, CPU, memory, and battery consequences of real-time work.
- Refactor freely when it improves correctness, cohesion, observability, or testability, while preserving behavior or providing a migration path.

### `esp32-expert`

**Default model**: Gemini 3.6 Flash — Medium

**Escalation model**: Gemini 3.1 Pro — High

**Use when**: ESP32/Arduino/C++, BLE server, Wi-Fi, MJPEG, camera buffers, I2C/SPI/UART, ToF sensors, FreeRTOS, watchdog, power, OTA architecture.

**Senior responsibilities**:

- Design for bounded memory, deterministic timing, recovery, and graceful degradation.
- Prefer non-blocking state machines, timers, queues, tasks, and explicit backpressure.
- Validate sensor input, BLE packets, network input, and timing assumptions.
- Monitor heap, stack, PSRAM, watchdog, brownout, reconnect, and camera buffer behavior.
- Refactor firmware structure when needed, but do not flash hardware without human approval.

### `latency-optimizer`

**Default model**: Gemini 3.7 Flash — Medium for evidence preparation

**Escalation model**: Gemini 3.1 Pro — High for conclusions and cross-layer changes

**Use when**: Latency, jitter, packet loss, frame drop, CPU/memory profiling, buffer tuning, timestamp correlation, reliability and observability.

**Senior responsibilities**:

1. Establish a reproducible baseline.
2. Identify the dominant bottleneck across the entire path.
3. Change one principal variable at a time.
4. Re-run the same workload.
5. Report mean, median, p95, p99, jitter, packet loss, frame drop, and resource impact where relevant.
6. Reject a local optimization if it damages correctness, battery life, usability, or accessibility.

### `integration-lead`

**Default model**: Gemini 3.1 Pro — High

**Use when**: Shared protocols, Android-firmware boundaries, schema changes, branch reconciliation, integration testing, final review.

**Senior responsibilities**:

- Establish and own interface contracts.
- Allow parallel proposals but prevent concurrent contract edits.
- Verify Android and firmware changes together.
- Reconcile worktrees and resolve incompatible assumptions.
- Block merges that lack boundary tests, safety review, or a clear rollback path.

## Parallel Execution Standard

Use Antigravity parallel agents only when task ownership and write boundaries are explicit.

### Recommended VNetra-Lite decomposition

```text
Agent A — Android implementation
Model: Gemini 3.6 Flash Medium or Gemini 3.7 Flash Medium
Owns: Android-side implementation and tests

Agent B — Firmware implementation
Model: Gemini 3.6 Flash Medium or Gemini 3.7 Flash Medium
Owns: ESP32-side implementation and build checks
Policy: sandbox; no upload

Agent C — Performance and evidence
Model: Gemini 3.5 Flash Medium for log analysis;
       Gemini 3.1 Pro High for bottleneck conclusions
Owns: baseline, instrumentation review, statistics, performance report

Agent D — Integration lead
Model: Gemini 3.1 Pro High
Owns: shared contract, compatibility, branch reconciliation, final test gate
```

### Mandatory parallel rules

1. Every editing subagent uses an isolated Git branch/worktree.
2. One agent owns each shared protocol, schema, build file, manifest, central model, or public API.
3. Other agents can inspect, review, and propose changes but cannot edit an owned shared interface concurrently.
4. Do not spawn parallel agents merely because models are available; use parallelism only when the coordination cost is lower than the expected gain.
5. Each agent must return changed files, tests, measurements, assumptions, unresolved risks, and a next action.
6. The integration lead must review all branch diffs before merge.
7. Firmware flashing, OTA deployment, production release, merge, deletion, and credential changes require human approval.

## Obsidian Context-Memory Protocol

Obsidian is the durable explicit-memory layer. It does not preserve hidden model conversation state; agents must write verified context deliberately.

### Session start

```yaml
required_reads:
  - project-local AGENTS.md
  - 05-Architecture/CONTEXT.md
  - latest relevant 00-Inbox/session-bridge-*.md
  - task-relevant notes in 02-Projects/ and decisions/
required_discovery:
  - git status
  - recent commits
  - current source-tree snapshot
  - relevant source, interface, and test files
```

### During the session

```yaml
rules:
  - Search Obsidian before loading broad historical context.
  - Load source code on demand, not the entire repository.
  - Append verified progress and measurements to the daily note.
  - Record decisions only after evidence and trade-off analysis.
  - Keep raw logs, CSVs, screenshots, and binaries outside narrative context notes; link to artifacts instead.
```

### Session end

```yaml
required_writes:
  - update the relevant component note with status, evidence, risk, and next step
  - append a concise summary to 01-Daily/YYYY-MM-DD.md
  - write/update a session bridge below 500 words
  - create an ADR only for architecture/protocol/concurrency/safety/persistence decisions
  - update CONTEXT.md only when system boundaries materially changed
```

### Context-window budget

| Artifact | Limit and purpose |
|---|---|
| `05-Architecture/CONTEXT.md` | Under approximately 1,200 words; current architecture and active facts |
| Session bridge | Under approximately 500 words; precise handoff to next session |
| Component note | Current state, evidence, risks, and links—not code transcript |
| Daily note | Append-only evidence log; archive monthly |
| Raw artifacts | Stored in project/versioned storage and linked from Obsidian |

## File-Structure Synchronization

Refresh the project structure note when source modules, packages, services, drivers, build targets, dependencies, interfaces, schemas, or protocols are added, removed, renamed, moved, or materially changed.

```bash
find . -type f \
  \( -name '*.kt' -o -name '*.java' -o -name '*.xml' -o -name '*.ino' -o \
     -name '*.cpp' -o -name '*.h' -o -name '*.gradle*' -o \
     -name 'platformio.ini' -o -name 'CMakeLists.txt' \) \
  -not -path './.git/*' | sort
```

Required structure update:

1. Compare the discovered snapshot with `03-Entities/file-structure.md`.
2. Update the note with timestamp, commit, and concise topology changes.
3. Update `CONTEXT.md` if a system boundary changed.
4. Add an ADR if the change is architectural.
5. Append a one-line record to the daily note.

Run this at session start, after structural commits, and during weekly review.

## RTK Guidance

Use RTK when supported to reduce repetitive command output.

- Aggressive: routine Git, successful Gradle/PlatformIO builds, ordinary test summaries.
- Moderate: failing tests, stack traces, ADB/serial diagnostics, latency data.
- Preserve raw output: evidence for thesis analysis, security incidents, or hardware faults until archived.

## Session Bridge Template

```markdown
# Session Bridge — YYYY-MM-DD HH:mm

## Project
- Name:
- Branch/worktree:
- Active subagent:
- Gemini model:
- Reason for model selection:

## Objective
-

## Completed
- [ ]

## Verified Evidence
- Build/test:
- Hardware test:
- Measurements:
- Commit or diff:

## Risks and Blockers
-

## Next Action
-

## Structure or Contract Changes
-
```

## Completion Gate

Before declaring a task complete, verify:

- The current source and Git state were inspected.
- The selected Gemini tier matched the task complexity and risk.
- Relevant tests, builds, and hardware checks were performed.
- Shared interfaces and protocols remain compatible or include a migration path.
- Performance claims include reproducible measurements.
- Irreversible actions remained human-gated.
- Obsidian contains a concise, verified handoff.
