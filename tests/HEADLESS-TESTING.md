# Headless Testing Infrastructure

**Last Updated**: 2025-11-05  
**Status**: ✅ Functional (T019r, T019s verified)

## Overview

Automated headless testing infrastructure for Village Overhaul plugin using Paper server + RCON + PowerShell automation.

## Prerequisites

- **Java 21+** (Paper 1.20.4 requirement)
  - Tested with Java 21
  - Scripts auto-detect installed Java versions
- **PowerShell 5.1+** (Windows baseline for CI)
- **Paper 1.20.4** (auto-downloaded by setup script)

## Quick Start

```powershell
# 1. Build the plugin
cd plugin
./gradlew build
cd ..

# 2. Setup Paper server (one-time)
.\scripts\ci\sim\run-headless-paper.ps1 -AcceptEula

# 3. Run tests
.\scripts\ci\sim\test-custom-villager-interaction.ps1
.\scripts\ci\sim\test-npc-performance.ps1 -VillagerCount 10 -PerfProfile Medium
```

## Test Scripts

### T019r: Custom Villager Interaction Test
**File**: `scripts/ci/sim/test-custom-villager-interaction.ps1`

**Purpose**: Validates custom villager spawning and interaction system compatibility

**What it tests**:
- ✅ Custom villager spawns via `/votest spawn-villager`
- ✅ RCON command functionality
- ✅ Server stability with custom villagers
- ✅ Plugin initialization and metrics
- ⚠️ Player interaction (requires bot player - future work)

**Output**: `custom-villager-test-results.json`

**Exit codes**:
- `0` = Test passed (critical validations met)
- `1` = Test failed (server crash, spawn failure, etc.)

### T019s: NPC Performance Test
**File**: `scripts/ci/sim/test-npc-performance.ps1`

**Purpose**: Validates performance with multiple custom villagers

**Parameters**:
- `-VillagerCount <int>` - Number of villagers to spawn (default: 10)
- `-Ticks <int>` - Ticks to simulate (default: 6000 = 5 min)
- `-PerfProfile <Low|Medium|High>` - Performance budget (default: Medium = 2ms)

**What it tests**:
- ✅ Spawns N custom villagers via RCON
- ✅ Server stability under load
- ✅ Process monitoring (CPU usage)
- ⚠️ Tick time metrics (pending tick engine integration)

**Output**: `npc-perf-test-results.json`

**Performance budgets**:
- Low: 5ms per village per tick
- Medium: 2ms per village per tick
- High: 1ms per village per tick

## Test Commands

The plugin provides `/votest` commands for automated testing:

### `/votest spawn-villager <type> [x] [y] [z]`
Spawn a custom villager at the specified location.

**Example**:
```
/votest spawn-villager merchant 0 64 0
```

**Response**: `Spawned custom villager 'merchant' with UUID: <uuid>`

### `/votest trigger-interaction <player> <villager-uuid>`
Programmatically trigger interaction between player and villager (bypasses rate limits).

**Example**:
```
/votest trigger-interaction TestPlayer 720747ad-e2a1-4b34-9bb2-f95f6f1d24a8
```

**Note**: Requires a real player to be online.

### `/votest metrics`
Dump current metrics to server logs.

### `/votest performance`
Report active villager count and performance stats.

## RCON Integration

Tests use RCON (Remote Console) to send commands to the running server.

**Configuration** (auto-configured by `run-headless-paper.ps1`):
- Port: `25575`
- Password: `test123`
- Enabled in: `test-server/server.properties`

**PowerShell module**: `scripts/ci/sim/BotPlayer.psm1`

**Example**:
```powershell
Import-Module .\scripts\ci\sim\BotPlayer.psm1
Send-RconCommand -Password "test123" -Command "list"
```

## BotPlayer Module Functions

The `BotPlayer.psm1` module provides helper functions for test automation:

### `Send-RconCommand`
Send commands to running Paper server via RCON protocol.

**Example**:
```powershell
$result = Send-RconCommand -Password "test123" -Command "votest spawn-villager merchant 0 64 0"
```

### `Wait-ForLogPattern`
Monitor server logs for specific patterns (used to detect server ready state, plugin initialization, etc.)

**Example**:
```powershell
$ready = Wait-ForLogPattern -LogFile "test-server/server.log" -Pattern "Done" -TimeoutSeconds 120
```

### `Enable-Rcon`
Programmatically enable RCON in server.properties (generates random password).

### `New-BotPlayer`, `New-CustomVillager`, `Invoke-PlayerInteraction`
Helper functions for future bot player simulation (requires additional server-side plugin).

## Architecture

```
┌─────────────────────────────────────────┐
│  PowerShell Test Script                 │
│  (test-custom-villager-interaction.ps1) │
└────────────┬────────────────────────────┘
             │
             │ 1. Start server process
             │ 2. Wait for "Done" in logs
             │ 3. Send RCON commands
             │ 4. Parse logs
             │ 5. Validate results
             ▼
┌─────────────────────────────────────────┐
│  Paper Server (Java 21+)                │
│  - Village Overhaul Plugin              │
│  - RCON enabled (port 25575)            │
│  - Headless mode (--nogui)              │
└────────────┬────────────────────────────┘
             │
             │ RCON commands
             │ (/votest spawn-villager, etc.)
             ▼
┌─────────────────────────────────────────┐
│  TestCommands.java                      │
│  - spawn-villager                       │
│  - trigger-interaction                  │
│  - metrics                              │
│  - performance                          │
└─────────────────────────────────────────┘
```

## Verified Tests (2025-11-05)

### T019r: Custom Villager Interaction
**Status**: ✅ PASS

**Results**:
- Custom villager spawned: ✅
- RCON commands functional: ✅
- Server stability: ✅
- Plugin initialization: ✅
- Player interaction: ⚠️ Requires bot player (future)

**Test output**:
```json
{
  "passed": true,
  "timestamp": "2025-11-05T18:14:51...",
  "validations": {
    "custom_villager_spawned": true,
    "interaction_logged": false,
    "project_contribution_made": false
  }
}
```

### T019s: NPC Performance
**Status**: ✅ PASS (stability validated)

**Results**:
- Villagers spawned: 5/5 ✅
- Server ran without crashes: ✅
- Process monitoring: ✅
- Tick time metrics: ⚠️ Pending tick engine integration

**Note**: Test validates server stability under load. Detailed performance metrics will be available once tick engine integrates metric collection.

## Limitations & Future Work

### Current Limitations
1. **No player simulation**: Tests validate commands work but can't simulate full player interactions
2. **Metrics pending**: `npc.tick_time_ms` metric collection requires tick engine integration
3. **Single-platform**: Currently Windows-only (PowerShell 5.1)

### Future Enhancements
1. **Bot player plugin**: Enable full interaction flow testing
2. **Metric integration**: Wire tick engine to emit `npc.tick_time_ms` to logs
3. **Cross-platform**: Add bash equivalents for Linux/macOS CI
4. **Bedrock testing**: Geyser integration for cross-edition validation

## Troubleshooting

### "Server failed to initialize"
- **Cause**: Java version too old
- **Fix**: Install Java 21+ from https://adoptium.net/
- Scripts auto-detect Java 21 at `C:\Program Files\Eclipse Adoptium\jdk-21\`

### "RCON connection failed"
- **Cause**: Server not running or RCON not enabled
- **Fix**: Ensure `test-server/server.properties` has:
  ```properties
  enable-rcon=true
  rcon.port=25575
  rcon.password=test123
  ```

### "Plugin not found"
- **Cause**: Plugin JAR not built/copied
- **Fix**: Run `cd plugin && ./gradlew build && cd ..`
- Setup script auto-copies from `plugin/build/libs/village-overhaul-*.jar`

### "Test times out"
- **Cause**: Server taking longer than 120s to start
- **Fix**: Increase timeout in test script or check logs for errors:
  ```powershell
  Get-Content test-server\test-custom-villager.log
  Get-Content test-server\test-custom-villager-error.log
  ```

## Files

### Scripts
- `scripts/ci/sim/run-headless-paper.ps1` - Paper server setup
- `scripts/ci/sim/run-scenario.ps1` - Main test harness with path validation (T026, T026a)
- `scripts/ci/sim/test-custom-villager-interaction.ps1` - T019r test
- `scripts/ci/sim/test-npc-performance.ps1` - T019s test
- `scripts/ci/sim/BotPlayer.psm1` - Helper module (RCON, log monitoring, etc.)

### Plugin Code
- `plugin/src/main/java/.../commands/TestCommands.java` - `/votest` commands
- `plugin/src/main/resources/plugin.yml` - Command registration

### Test Artifacts
- `custom-villager-test-results.json` - T019r output
- `npc-perf-test-results.json` - T019s output
- `state-snapshot.json` - T026/T026a scenario snapshot
- `test-server/` - Server directory (auto-created)

## T026a: Pathfinding Concurrency Cap & Waypoint Cache Tests

**Added**: 2025-11-09  
**Status**: ✅ Implemented  
**Location**: `scripts/ci/sim/run-scenario.ps1`

### What T026a Tests

#### 1. Pathfinding Node Cap Enforcement
Validates that A* pathfinding respects `MAX_NODES_EXPLORED` limit (currently 5000 nodes).

**Test Pattern**:
```regex
\[PATH\] A\* FAILED: node limit reached \(explored=([0-9]+)/([0-9]+)
```

**Acceptance Criteria**:
- ✅ Explored nodes never exceed MAX_NODES_EXPLORED cap
- ✅ Failed paths gracefully abort when limit reached
- ✅ Log reports: `(explored=5000/5000, obstacles=N, maxCost=X.X)`

**Performance Monitoring**:
- Tracks average node exploration across successful paths
- Warns if average >3000 nodes (indicates complex terrain)
- Reports exploration range (min/max/avg) per test run

**Example Output**:
```
=== Pathfinding Node Cap Validation (T026a) ===
Node cap enforcement detected: 2 path(s) hit limit
  Explored: 5000 / Cap: 5000
  Explored: 5000 / Cap: 5000
OK All failed paths respected MAX_NODES_EXPLORED cap

Successful pathfinding analysis:
  Total successful paths: 8
  Node exploration range: 124 - 2847 (avg: 1245.3)
```

#### 2. Waypoint Cache Behavior
Monitors path network caching and regeneration patterns.

**Test Pattern**:
```regex
\[STRUCT\] Path network complete: village=([a-f0-9\-]+)
```

**Current Implementation Notes**:
- ✅ Village-level path network cache (HashMap<UUID, PathNetwork>)
- ⚠️ **Full waypoint-level cache not yet implemented** (future work)
- ⚠️ **Terrain-triggered invalidation not yet implemented** (future work)

**Acceptance Criteria**:
- ✅ Each village generates paths exactly once (cache working)
- ⚠️ Regeneration detected → potential cache invalidation (not yet implemented)
- ℹ️ Test framework ready for future waypoint cache validation

**Example Output**:
```
=== Waypoint Cache Validation (T026a) ===
Path network cache entries: 3 village(s)
OK All villages generated paths exactly once (cache working as expected)

NOTE: Full waypoint-level cache and invalidation not yet implemented (future work)
```

### Running T026a Tests

```powershell
# Standard scenario run (includes T026a validation)
.\scripts\ci\sim\run-scenario.ps1 -Ticks 3000

# Stress test with complex terrain (trigger node cap)
.\scripts\ci\sim\run-scenario.ps1 -Ticks 6000 -Seed 99999
```

### Interpreting Results

**Node Cap Tests**:
- 🟢 **PASS**: All failed paths show `explored ≤ cap`
- 🔴 **FAIL**: Any path exceeds MAX_NODES_EXPLORED
- 🟡 **WARNING**: High average node exploration (>3000)

**Cache Tests**:
- 🟢 **PASS**: Each village generates paths once only
- 🟡 **WARNING**: Regeneration detected (check terrain changes)
- 🔵 **INFO**: No cache activity (paths may not be generated)

### Future Work (T026a Extensions)

Planned enhancements once waypoint cache is fully implemented:

1. **Waypoint-level caching**:
   - Track individual waypoint segments (not just full paths)
   - Reuse shared path segments between buildings
   - Log pattern: `[PATH] Waypoint cache hit: segment X->Y`

2. **Terrain-triggered invalidation**:
   - Invalidate cached paths when terrain changes
   - Log pattern: `[PATH] Cache invalidated: terrain change at (x,y,z)`
   - Test: Modify terrain, regenerate paths, verify cache miss

3. **Concurrent planner cap**:
   - Limit simultaneous A* searches (e.g., max 3 concurrent)
   - Log pattern: `[PATH] Planner queue: active=N, queued=M`
   - Test: Trigger many path generations, verify queue behavior

## Constitution Compliance

Per Constitution v1.1.0, Amendment 2025-11-05:

**Scripting & CI Portability** (Windows PowerShell 5.1 baseline):
- ✅ ASCII-only output (OK/X/! instead of Unicode)
- ✅ Single-quoted regex with explicit `[0-9]` classes
- ✅ Simple readiness checks (substring 'Done')
- ✅ Parser-validated with `Get-Command -Syntax`

**Observability** (Principle VII):
- ✅ Structured test results in JSON
- ✅ Log parsing for validation
- ✅ Metrics framework integrated

**Security** (Principle IX):
- ✅ RCON password for test environment only
- ✅ Test commands require `villageoverhaul.test` permission
- ✅ Server-side validation in all commands
