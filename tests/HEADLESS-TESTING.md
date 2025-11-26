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
# Option 1: Build + Copy JAR + Test (recommended)
.\scripts\ci\sim\build-and-test.ps1

# Option 2: Manual steps
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

## Build and Test Helper

**File**: `scripts/ci/sim/build-and-test.ps1`

**Purpose**: Build plugin, copy JAR to test-server, and run scenario test in one command. Eliminates JAR copy mismatches and ensures tests always use latest code.

**Usage**:
```powershell
# Default: Build (with tests) + Run 3000-tick scenario
.\scripts\ci\sim\build-and-test.ps1

# Build (skip unit tests) + Run 6000-tick scenario
.\scripts\ci\sim\build-and-test.ps1 -Ticks 6000 -SkipTests

# Skip build + Use existing JAR + Run test
.\scripts\ci\sim\build-and-test.ps1 -SkipBuild

# Custom seed
.\scripts\ci\sim\build-and-test.ps1 -Seed 99887
```

**Parameters**:
- `-Ticks <int>` - Number of ticks to simulate (default: 3000)
- `-Seed <long>` - World seed for deterministic generation (default: 12345)
- `-SkipBuild` - Skip Gradle build, use existing JAR
- `-SkipTests` - Pass `-x test` to Gradle (skip unit tests during build)

**What it does**:
1. Builds plugin with `./gradlew build` (or `-x test` if `-SkipTests`)
2. Copies `plugin/build/libs/village-overhaul-*.jar` to `test-server/plugins/VillageOverhaul.jar`
3. Cleans remapped plugin cache (eliminates "ambiguous plugin name" warnings)
4. Runs `run-scenario.ps1` with specified ticks/seed

## CI Integration

- The repository includes a GitHub Actions workflow which runs several headless tests on Windows runners.
- Workflow path: `.github/workflows/fixed-layout-determinism.yml`
- The workflow installs JDK 21 and runs these harnesses to validate deterministic artifacts and basic behaviour:
  - `scripts/ci/sim/test-fixed-layout-determinism.ps1` (T026d determinism check)
  - `scripts/ci/sim/test-custom-villager-interaction.ps1` (T019r)
  - `scripts/ci/sim/test-npc-performance.ps1` (T019s - small workload under CI)

**Exit codes**:
- `0` = Build and test passed
- `1` = Build failed or JAR not found
- Other = Test harness exit code

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
- `scripts/ci/sim/test-fixed-layout-determinism.ps1` - New: run two fixed-layout repeats and compare persisted village artifacts for determinism
  - CI runs for fixed-layout determinism now fail early on ZERO-PLACEMENT; diagnostics and placement rejection artifacts are saved under `test-server-fixed-*/artifacts/` for triage (see T026d15).
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

## T026b: Distant Building Path Generation Test

**Purpose**: Validates path generation between buildings separated by large distances (≥120 blocks) while respecting the `MAX_SEARCH_DISTANCE` limit (200 blocks).

**Status**: ✅ Implemented (2025-11-09)

### What T026b Tests

#### 1. Distance Analysis
Parses pathfinding logs to identify attempted paths by distance:
- **Distant paths** (120-200 blocks): Should attempt generation
- **Out-of-range paths** (>200 blocks): Should be gracefully rejected

**Log pattern**:
```
[PATH] A* search start: from (X1,Y1,Z1) to (X2,Z2), distance=D.D
```

#### 2. Graceful Handling
For paths between 120-200 blocks apart, validates one of three outcomes:
- ✅ **SUCCESS**: Path generated successfully
  - Pattern: `[STRUCT] Path found: distance=D.D, blocks=N`
- ✅ **SKIP**: Distance too far (graceful rejection)
  - Pattern: `[STRUCT] Path distance too far: D.D blocks (max 200)`
- ✅ **FAILURE**: No valid path found (graceful failure)
  - Pattern: `[STRUCT] No path found from (X1,Y1,Z1) to (X2,Y2,Z2)`

#### 3. Out-of-Range Rejection
Paths >200 blocks should be rejected before A* search:
- Pattern: `[STRUCT] Path distance too far: D.D blocks (max 200)`
- Validates rejection count matches detected out-of-range attempts

### Example Output

```
=== Distant Building Path Generation (T026b) ===
Path distance analysis:
  Total paths attempted: 15
  Distant paths (120-200 blocks): 3
  Out-of-range paths (>200 blocks): 1

Validating distant path generation:
  OK Path at 145.2 blocks: (100,50) -> (245,55) (SUCCESS)
  OK Path at 167.8 blocks: (200,75) -> (368,80) (FAILED - graceful)
  OK Path at 189.0 blocks: (150,60) -> (339,65) (SUCCESS)

OK All distant paths handled gracefully (success=2, failure=1)

OK Out-of-range paths properly rejected (1 path(s) > 200 blocks)
```

### Running T026b Tests

```powershell
# Standard scenario run (includes T026b validation)
.\scripts\ci\sim\run-scenario.ps1 -Ticks 3000

# Large village to force distant building pairs
.\scripts\ci\sim\run-scenario.ps1 -Ticks 6000 -Seed 42
```

### Interpreting Results

**Distance Detection**:
- 🟢 **PASS**: Distant paths (120-200 blocks) detected and analyzed
- 🟡 **WARNING**: No distant paths detected (buildings placed closer)
- 🔵 **INFO**: Expected if village layout is compact

**Graceful Handling**:
- 🟢 **PASS**: All distant paths show success/skip/failure outcome
- 🔴 **FAIL**: Distant path with NO DATA (missing log entries)

**Range Enforcement**:
- 🟢 **PASS**: All paths >200 blocks rejected with proper log
- 🔴 **FAIL**: Out-of-range rejection incomplete

### Test Scenarios

**Typical compact village** (buildings within 50-80 blocks):
- No distant paths detected
- Result: ✅ PASS (expected behavior)

**Large spread village** (buildings 100-180 blocks apart):
- Multiple distant paths detected
- Mix of SUCCESS/FAILURE outcomes
- Result: ✅ PASS if all graceful

**Extreme distance** (buildings >200 blocks apart):
- All attempts rejected before A* search
- Result: ✅ PASS with proper rejection logs

### Implementation Notes

**Current Behavior**:
- `MAX_SEARCH_DISTANCE = 200` (hardcoded in PathServiceImpl.java)
- Distance check happens BEFORE A* search (efficient early rejection)
- Failed paths (obstacles, node cap) are distinguished from skipped paths (too far)

**Tolerance**: ±5 blocks for distance matching (accounts for floating-point rounding)

## T026c: Terrain Cost Accuracy Integration Test

**Purpose**: Validates that A* pathfinding correctly applies terrain-aware cost functions and prefers lower-cost routes (flat terrain) over higher-cost alternatives (water, steep slopes).

**Status**: ✅ Implemented (2025-11-09)

### What T026c Tests

#### 1. Terrain Cost Breakdown Analysis
Parses pathfinding success logs to analyze the terrain composition of generated paths:
- **Flat tiles**: Level terrain (cost = 1.0 per tile)
- **Slope tiles**: 1-block elevation change (cost ≈ 3.0 per tile)
- **Water tiles**: Water or lava blocks (cost = 11.0+ per tile)
- **Steep tiles**: 2+ blocks elevation change (high cost, approaching obstacle threshold)

**Log pattern**:
```
[PATH] A* SUCCESS: Goal reached after exploring N nodes (path=P tiles, cost=C.C, flat=F, slope=S, water=W, steep=T)
```

#### 2. Cost-Aware Routing Validation
Validates that the A* algorithm demonstrates cost-aware behavior:
- ✅ **Water avoidance**: ≥80% of paths should avoid water (WATER_COST=10.0 strongly discourages)
- ✅ **Steep avoidance**: ≥70% of paths should avoid steep terrain (SLOPE_COST_MULTIPLIER=2.0 discourages)
- ✅ **Cost efficiency**: ≥50% of paths should be low-cost (avg ≤1.5 cost per tile, mostly flat)

#### 3. Path Cost Categories
Paths are categorized by average cost per tile:
- **Low-cost** (≤1.5 avg): Mostly flat terrain, optimal routes
- **Moderate-cost** (1.5-3.0 avg): Some slopes, acceptable routes
- **High-cost** (>3.0 avg): Water or steep terrain, suboptimal but necessary

### Terrain Cost Constants

From `PathServiceImpl.java`:
```java
FLAT_COST = 1.0                 // Base cost per tile
SLOPE_COST_MULTIPLIER = 2.0     // Per block of elevation change
WATER_COST = 10.0               // Water/lava penalty
OBSTACLE_COST = 20.0            // Impassable threshold
MAX_ACCEPTABLE_SLOPE = 3.0      // Blocks per horizontal distance
```

### Example Output

```
=== Terrain Cost Accuracy Validation (T026c) ===
Terrain cost analysis:
  Total paths analyzed: 12
  Low-cost paths (avg <=1.5 per tile): 8
  Moderate-cost paths (avg 1.5-3.0): 3
  High-cost paths (avg >3.0): 1
  Water-avoiding paths: 11 / 12
  Steep-avoiding paths: 10 / 12

  OK Water avoidance: 91.7% of paths avoid water
  OK Steep avoidance: 83.3% of paths avoid steep terrain
  OK Cost efficiency: 66.7% of paths are low-cost (mostly flat)

OK Terrain-cost routing validation passed (3/3 checks passed)
```

### Running T026c Tests

```powershell
# Standard scenario run (includes T026c validation)
.\scripts\ci\sim\run-scenario.ps1 -Ticks 3000

# Varied terrain seed to test cost-aware routing
.\scripts\ci\sim\run-scenario.ps1 -Ticks 6000 -Seed 99887

# Multiple villages to accumulate path statistics
.\scripts\ci\sim\run-scenario.ps1 -Ticks 12000
```

### Interpreting Results

**Water Avoidance**:
- 🟢 **PASS** (≥80%): Pathfinding strongly avoids water routes
- 🟡 **WARNING** (60-80%): Moderate avoidance, some water crossings necessary
- 🔴 **FAIL** (<60%): Pathfinding not respecting WATER_COST penalty

**Steep Avoidance**:
- 🟢 **PASS** (≥70%): Pathfinding prefers gentle slopes
- 🟡 **WARNING** (50-70%): Moderate avoidance, terrain may be hilly
- 🔴 **FAIL** (<50%): Pathfinding not respecting slope costs

**Cost Efficiency**:
- 🟢 **PASS** (≥50%): Most paths are efficient (flat terrain)
- 🟡 **WARNING** (30-50%): Moderate efficiency, challenging terrain
- 🔵 **INFO** (<30%): Expected in mountainous/water-heavy biomes

### Test Scenarios

**Flat plains village** (minimal elevation change):
- 90%+ low-cost paths expected
- 95%+ water avoidance expected
- Result: ✅ PASS with high efficiency

**Rolling hills village** (moderate elevation):
- 50-70% low-cost paths expected
- More slope tiles, fewer steep tiles
- Result: ✅ PASS with moderate efficiency

**Riverside village** (water obstacles):
- High water avoidance rate critical
- Some paths may require moderate slopes to avoid water
- Result: ✅ PASS if water avoided despite elevation

**Mountain village** (steep terrain):
- Lower cost efficiency expected (challenging terrain)
- Steep avoidance still important (prefer many gentle slopes over few steep climbs)
- Result: ✅ PASS if avoidance rates meet thresholds despite terrain

### Implementation Notes

**Cost Calculation**:
- Base cost (FLAT_COST) applied to every tile
- Slope cost added per block of elevation change: `yDiff * SLOPE_COST_MULTIPLIER`
- Water penalty (WATER_COST) added if tile is water/lava
- Total path cost = sum of all tile costs

**A* Heuristic**:
- Uses Manhattan distance (straight-line approximation)
- Does not predict terrain costs (A* discovers actual costs during search)
- Cost-aware behavior emerges from A* algorithm's optimal path finding

**Categorization Logic**:
- **Flat**: No elevation change (yDiff = 0)
- **Slope**: 1 block elevation change (yDiff = 1)
- **Steep**: 2+ blocks elevation change (yDiff ≥ 2)
- **Water**: Tile material is WATER or LAVA (checked at destination)

**Limitations**:
- Test analyzes final paths only (does not compare rejected alternatives)
- High-cost paths may be necessary if no low-cost route exists
- Terrain composition varies by seed and biome

### Future Enhancements (Not Yet Implemented)

## T026c1: Controlled Route Comparison (Manual Playtest Guidance)

**Purpose**: Provide a manual, low-effort method to verify that A* prefers a slightly longer flat route over a shorter high-cost (water or steep) route when the length difference is within ~20%. This extends T026c without full automation.

**Status**: 🟡 Partially Implemented (commands + guidance) — Automation deferred (2025-11-09)

### What Is Implemented
| Component | Status | Notes |
|-----------|--------|-------|
| `/votest place-obstacle water <x> <z> <radius>` | ✅ | Creates circular water patch (WATER tiles) |
| `/votest place-obstacle steep <x> <z> <width>` | ✅ | Creates 4-block high stone wall (STEEP tiles) |
| Logging of terrain costs per successful path | ✅ | Provided by T026c instrumentation |
| Harness automation of dual-route setup | ❌ | Deferred (complex, low ROI) |
| Comparative rejection logging (alternative route costs) | ❌ | Future PathService enhancement |

### Manual Test Scenario (Water vs Flat)
1. Start a test world near plains (flat area).
2. Pick two target points A and B roughly 50 blocks apart (flat route reference). Example: `A=(0,64,0)`, `B=(50,64,0)`.
3. Place a building or village anchor at A and another at B (using existing create/generate commands if available or placeholder blocks).
4. Create a water patch that forms a shorter diagonal route (~40 blocks) between A and B:
  ```powershell
  /votest place-obstacle water 25 10 6
  ```
  Adjust center/radius until the direct diagonal requires crossing water.
5. Trigger path generation (e.g., `/votest generate-paths <village-id>` or action that invokes path planner).
6. Inspect logs for chosen path cost line:
  ```text
  [PATH] A* SUCCESS: Goal reached after exploring N nodes (path=P tiles, cost=C.C, flat=F, slope=S, water=W, steep=T)
  ```
7. EXPECTATION: The path should avoid water (W ≈ 0) even if P (tile count) is ~10–20% longer than the hypothetical water-crossing diagonal.

### Manual Test Scenario (Steep vs Flat)
1. In a flat corridor between A and B (~50 blocks apart), place a steep wall near midpoint:
  ```powershell
  /votest place-obstacle steep 25 0 8
  ```
2. This creates a forced elevation barrier where a shorter route would climb the wall (~45 blocks total) versus a longer flat detour (~50 blocks).
3. Trigger path generation.
4. EXPECTATION: Chosen path has `steep=0` (or minimal) and slightly higher P (tile count) than hypothetical steep climb.

### Evaluating Results
Record observed path metrics:
| Scenario | Flat Tiles | Slope Tiles | Water Tiles | Steep Tiles | Path Length | Avg Cost |
|----------|------------|------------|------------|-------------|-------------|----------|
| Water vs Flat | F | S | W | T | P | C/P |
| Steep vs Flat | F | S | W | T | P | C/P |

PASS Criteria (Manual):
- Water scenario: `water=0` AND `pathLength <= flatReference * 1.20`
- Steep scenario: `steep=0` AND `pathLength <= flatReference * 1.20`
- Avg cost (C/P) for chosen route significantly lower than hypothetical high-cost route (estimation acceptable)

### Why Automation Deferred
- Requires deterministic structure placement + coordinate bookkeeping
- Adds complexity to CI without proportionate regression value
- Current terrain-cost breakdown already flags mis-weighted penalties

### Future Automation Hooks (When Worthwhile)
- PathServiceImpl: Emit `[PATH] ALTERNATIVE rejected: length=L, estCost=K.K, reason=water|steep` lines
- Harness: Parse pairs of chosen vs rejected costs and assert chosenCost < rejectedCost AND chosenLength <= rejectedLength * 1.20

### Quick Checklist (Manual Execution)
- [ ] Water patch placed creating shorter water route
- [ ] Flat alternative exists within +20% length
- [ ] Generated path avoids water (water=0)
- [ ] Steep wall placed creating shorter steep route
- [ ] Generated path avoids steep (steep=0)
- [ ] Logs captured for both paths
- [ ] Results table filled out

### Command Reference
```text
/votest place-obstacle water <x> <z> <radius>
/votest place-obstacle steep <x> <z> <width>
```
Use multiple water patches or wall width adjustments to tune alternative route lengths.

### Next Steps
- (Optional) Add alternative route rejection logging to PathServiceImpl
- (Optional) Script RCON sequence for placement + generation in dedicated test file
- (Optional) Integrate cost comparison table export to JSON


**Comparative Route Analysis** (future work):
- Construct explicit test scenarios with known flat/water/steep alternatives
- Place buildings such that two routes exist: one flat (long) vs one short (steep/water)
- Assert A* chooses flat route if length difference is comparable (<20% longer)

**Terrain Manipulation** (future work):
- Programmatically place water/steep obstacles in test world
- Create controlled test cases with deterministic route options
- Validate cost function accuracy against hand-calculated expected costs

**Cost Function Tuning** (future work):
- Adjust WATER_COST, SLOPE_COST_MULTIPLIER based on test results
- Balance between route optimality and path diversity
- Ensure cost penalties match gameplay intentions (e.g., Romans prefer straight roads even if sloped)

---

## T026d: Deterministic Path-from-Seed Check

**Purpose**: Verify that path generation with the same seed produces identical path coordinates (determinism) and that different seeds produce different paths (variance).

**Status**: ✅ Implemented (2025-11-09)

**Determinism Stabilized**: ✅ (2025-11-25)

Sample dual-run PASS output (Run1 == Run2):

```text
=== Deterministic Path-from-Seed Check (T026d) ===
Run 1 seed=12345: 8 path hashes captured
Run 2 seed=12345: 8 path hashes captured
Compare: Run1 == Run2 → PASS (all 8 hashes match, order preserved)
Run 3 seed=67890: 8 path hashes captured (different from Run1/2) → VARIANCE PASS
```

### What Is Implemented
| Component | Status | Notes |
|-----------|--------|-------|
| Path coordinate hashing in PathServiceImpl | ✅ | MD5 hash of ordered (x,y,z) coordinates |
| `[PATH] Determinism hash` logging | ✅ | Logs hash + node count for every path |
| Harness parsing of determinism hashes | ✅ | Groups by hash value, reports duplicates |
| Hash comparison validation | 🟡 | Basic grouping only (full multi-run test deferred) |

### How It Works

**Code Changes** (`PathServiceImpl.java`):
```java
// After A* pathfinding completes, compute hash of ordered path nodes
String pathHash = computePathHash(path);
LOGGER.info(String.format("[PATH] Determinism hash: %s (nodes=%d)", pathHash, path.size()));

private String computePathHash(List<PathNode> path) {
    // MD5 hash of "x1,y1,z1;x2,y2,z2;..." format
}
```

**Log Output Example**:
```text
[PATH] A* SUCCESS: Goal reached after exploring 245 nodes (path=45 tiles, cost=52.3, flat=40, slope=5, water=0, steep=0)
[PATH] Determinism hash: 3a7f8c2d1e9b4f6a8c2d1e9b4f6a8c2d (nodes=45)
```

**Harness Validation** (`run-scenario.ps1`):
- Parses all `[PATH] Determinism hash` lines
- Groups paths by hash value
- Reports:
  - Total unique hashes
  - Duplicate hash counts (same hash appearing multiple times)
  - Validation status: identical hashes = deterministic, all unique = variance

### Running the Test

**Single Run (Current)** — Validates hash generation only:
```powershell
.\scripts\ci\sim\run-scenario.ps1 -Ticks 3000 -Seed 12345
```

**Expected Output**:
```text
=== Deterministic Path-from-Seed Check (T026d) ===
INFO Found 8 path determinism hashes
INFO Unique path hashes: 8
INFO All path hashes unique (expected if using different building pairs)
INFO To test determinism: run twice with same seed and compare hashes
```

**Full Determinism Test (Automated)** — Runs 3 times and compares hashes:

```powershell
.\scripts\ci\sim\test-path-determinism.ps1
```

This script automates the full T026d acceptance criteria:
1. **Run 1** (Seed A = 12345): Captures baseline path hashes
2. **Run 2** (Seed A = 12345): Re-runs with same seed, compares hashes
3. **Run 3** (Seed B = 67890): Runs with different seed, verifies variance

**Expected Results**:
- ✅ Determinism: Run 1 and Run 2 produce identical hash sequences
- ✅ Variance: Run 3 produces different hashes from Runs 1/2

**Known Limitation**: Structure placement must be deterministic for path determinism test to succeed. If structure placement fails intermittently (0 placements in one run, 3+ in another), the test will report FAIL for determinism even if path generation itself is deterministic. This is expected until structure placement reproducibility issues are resolved.

**Manual Test (Alternative)** — Compare hashes across runs:
1. Run with seed A twice:
   ```powershell
   .\scripts\ci\sim\run-scenario.ps1 -Ticks 3000 -Seed 12345 | Out-File run1.log
   .\scripts\ci\sim\run-scenario.ps1 -Ticks 3000 -Seed 12345 | Out-File run2.log
   ```
2. Extract hashes from both logs:
   ```powershell
   Select-String '\[PATH\] Determinism hash' run1.log
   Select-String '\[PATH\] Determinism hash' run2.log
   ```
3. EXPECTATION: Identical hash sequences (same order, same values)

4. Run with seed B:
   ```powershell
   .\scripts\ci\sim\run-scenario.ps1 -Ticks 3000 -Seed 67890 | Out-File run3.log
   Select-String '\[PATH\] Determinism hash' run3.log
   ```
5. EXPECTATION: Different hashes from seed A runs

### Acceptance Criteria

✅ **Hash Generation**:
- Every successful A* path logs a determinism hash
- Hash format: 32-character hex string (MD5)
- Node count matches path length

✅ **Reproducibility (Same Seed)**:
- Multiple runs with same seed produce identical hash sequences
- Hash order matches building placement order (deterministic)

✅ **Variance (Different Seeds)**:
- Different seeds produce different hash values
- Validates that seed affects path randomization/tie-breaking

### Current Limitations

🟡 **Structure Placement Determinism Dependency**:
- Path determinism test requires deterministic structure placement
- If structure placement fails intermittently (e.g., 3 placements in Run 1, 0 in Run 2), no paths will be generated for comparison
- Current test result (2025-11-09): Variance confirmed ✅ (Seed A ≠ Seed B), but determinism test blocked by structure placement issue
- Resolution: Once structure placement is deterministic, re-run `test-path-determinism.ps1` for full validation

🟡 **Manual Verification Available**:
- Even with structure placement variability, hash generation and variance can be manually verified
- Extract hashes from successful runs: `Select-String '\[PATH\] Determinism hash' logs/*.log`
- Compare hash values manually to confirm different seeds produce different hashes

### Future Enhancements

✅ **Automated Multi-Run Comparison** (IMPLEMENTED):
```powershell
.\scripts\ci\sim\test-path-determinism.ps1
# - Runs with seed A twice, compares hash sequences
# - Runs with seed B once, verifies variance
# - Reports PASS/FAIL for determinism + variance
# - Saves artifacts: determinism-test-seedA-run1.log, etc.
```

**Hash Artifact Storage** (proposed):
```powershell
# Capture hashes to JSON for regression testing
# test-server/logs/path-hashes-seed-12345.json
{
  "seed": 12345,
  "hashes": ["3a7f8c2d...", "9b4f6a8c..."],
  "timestamp": "2025-11-09T12:34:56Z"
}
```

**Path Coordinate Export** (debugging, proposed):
```text
# Optional: log full coordinate list for manual inspection
[PATH] Path coordinates: (100,64,200);(101,64,201);...
```

### Integration with T026a (Cache Testing)

T026d complements T026a waypoint cache tests:
- **T026a**: Validates cache hit/miss behavior and invalidation
- **T026d**: Validates cache KEY stability (same seed = same coordinates)

Together they ensure:
1. Paths are deterministic (T026d)
2. Cached paths are reused correctly (T026a)
3. Cache invalidation triggers recalculation (T026a)

### Quick Checklist

✅ **Automated Full Test** (recommended):
- [ ] Run `.\scripts\ci\sim\test-path-determinism.ps1`
- [ ] Verify "Variance Test: PASS" (different seeds produce different hashes)
- [ ] Check for "Determinism Test: FAIL" due to structure placement issues (expected until structure placement is deterministic)
- [ ] Review artifacts: `test-server/logs/determinism-test-seed*.log`

**Single Run** (Hash Generation):
- [ ] Run `.\scripts\ci\sim\run-scenario.ps1 -Ticks 3000`
- [ ] Verify `[PATH] Determinism hash` lines present in logs
- [ ] Harness reports unique hash count
- [ ] No "hash-error" values (MD5 algorithm available)

Multi-Run (Determinism):
- [ ] Run with same seed twice, compare hash sequences
- [ ] Assert identical hashes in same order
- [ ] Run with different seed, assert different hashes

### Example Log Analysis

**Seed 12345 (Run 1)**:
```text
[PATH] Determinism hash: a1b2c3d4e5f6... (nodes=45)
[PATH] Determinism hash: f6e5d4c3b2a1... (nodes=38)
[PATH] Determinism hash: 123456789abc... (nodes=52)
```

**Seed 12345 (Run 2)** — Expected (identical):
```text
[PATH] Determinism hash: a1b2c3d4e5f6... (nodes=45)  ✅

---

## T026d3: Deterministic Re-Seat Logic

**Purpose**: Verify that when structure placement attempts fail, the retry sequence follows a stable, deterministic order with no early exits based on timing or non-deterministic collection iteration.

**Status**: ✅ Complete (2025-11-23)

### What Is Implemented

| Component | Status | Notes |
|-----------|--------|-------|
| Deterministic candidate ordering | ✅ | Sorted by distance², then X, then Z |
| Fixed filter sequence | ✅ | Collision → spacing relaxation |
| No timing-based early exits | ✅ | All candidates collected before filtering |
| Retry sequence hash logging | ✅ | MD5 hash of candidate coordinates |
| Harness validation | 🟡 | Manual verification via log parsing |

### How It Works

**Architecture**:
- `VillagePlacementServiceImpl.findSuitablePlacementPosition()` collects ALL candidate sites in a deterministic spiral pattern
- Candidates sorted by: (1) distance² from origin, (2) X coordinate, (3) Z coordinate
- Filters applied in fixed sequence: (1) collision check, (2) spacing relaxation if needed
- No hash-based iteration, no concurrent access, no timing dependencies

**Code Changes** (`VillagePlacementServiceImpl.java`):
```java
// Collect ALL candidates first (no early exit during spiral search)
List<CandidateSite> allCandidates = new ArrayList<>();
for (int radius = 0; radius <= maxRadius; radius += gridSize) {
    for (int dx = -radius; dx <= radius; dx += gridSize) {
        for (int dz = -radius; dz <= radius; dz += gridSize) {
            // ... collect candidate ...
            allCandidates.add(new CandidateSite(x, y, z, distSq, dx, dz));
        }
    }
}

// Sort by deterministic key
allCandidates.sort((a, b) -> {
    int distCompare = Integer.compare(a.distanceSquared, b.distanceSquared);
    if (distCompare != 0) return distCompare;
    int xCompare = Integer.compare(a.x, b.x);
    if (xCompare != 0) return xCompare;
    return Integer.compare(a.z, b.z);
});

// Apply filters in fixed sequence
for (CandidateSite candidate : allCandidates) {
    // Filter 1: collision check (with spacing buffer)
    // Filter 2: spacing relaxation (if collision fails)
    // ... deterministic processing ...
}

// If all candidates rejected, compute retry sequence hash
String retryHash = computeRetrySequenceHash(allCandidates);
LOGGER.warning(String.format("[STRUCT] findSuitable: No valid placement found ... retryHash=%s", retryHash));
```

**Log Output Example** (Failure Case):
```text
[STRUCT] findSuitable: No valid placement found within radius=256 checked=1024 rejected=1024 seed=12345 retryHash=3a7f8c2d1e9b4f6a8c2d1e9b4f6a8c2d
```

**Log Output Example** (Success Case):
```text
[STRUCT] findSuitable: Found candidate at offset=(12,-8) pos=(112,64,192) dist²=208 checked=45 rejected=44 seed=12345
```

### Running the Test

**Single Run** — Validates hash logging only:
```powershell
.\scripts\ci\sim\run-scenario.ps1 -Ticks 3000 -Seed 12345
```

**Look for** `[STRUCT] findSuitable` logs:
- If placement succeeds: No retry hash (found valid site)
- If placement fails: `retryHash=<32-hex>` present

**Multi-Run Determinism Test** — Compare retry hashes across same-seed runs:
```powershell
# Run 1 with seed A
.\scripts\ci\sim\run-scenario.ps1 -Ticks 3000 -Seed 99999 | Out-File run1.log

# Run 2 with seed A (should match)
.\scripts\ci\sim\run-scenario.ps1 -Ticks 3000 -Seed 99999 | Out-File run2.log

# Run 3 with seed B (should differ)
.\scripts\ci\sim\run-scenario.ps1 -Ticks 3000 -Seed 88888 | Out-File run3.log

# Extract retry hashes
Select-String 'retryHash=' run1.log
Select-String 'retryHash=' run2.log
Select-String 'retryHash=' run3.log
```

**Expected Results**:
- ✅ **Determinism**: Run 1 and Run 2 produce identical `retryHash` values (if both fail at same structure)
- ✅ **Variance**: Run 3 produces different `retryHash` from Runs 1/2 (different seed → different candidate coordinates)

### Acceptance Criteria

✅ **Deterministic Candidate Ordering**:
- All candidates collected before filtering (no early exits)
- Sorted by stable key: distance², X, Z (no timing dependencies)
- Filters applied in fixed sequence: collision → spacing relaxation

✅ **Hash Logging**:
- Failure cases include `retryHash=<32-hex>` in log output
- Hash computed from ordered "x1,y1,z1;x2,y2,z2;..." candidate coordinates
- Same seed → identical hash, different seed → different hash

✅ **No Non-Deterministic Behavior**:
- No hash-based collection iteration (e.g., HashSet, HashMap iteration)
- No timing-based early exits (e.g., timeout checks during candidate search)
- No concurrent access to shared candidate lists
- Chunk loading synchronized (ensures terrain queries are deterministic)

### Verification Checklist

**Code Audit** (Completed):
- [X] No `HashSet`/`HashMap` iteration over candidates
- [X] No early exits based on `System.currentTimeMillis()` or timeout checks
- [X] All candidates collected before filtering begins
- [X] Sorting uses stable, deterministic comparator
- [X] Filters always applied in same order

**Log Validation** (Manual):
- [ ] Run with pathological seed (many failures expected)
- [ ] Verify `retryHash=` present in failure logs
- [ ] Run twice with same seed, compare retry hashes (should match)
- [ ] Run with different seed, verify different retry hash

**Integration with Determinism Tests**:
- T026d3 complements T026d (path determinism) and T026d2 (candidate ordering)
- Together they ensure:
  1. Candidate search is deterministic (T026d2, T026d3)
  2. Paths are deterministic (T026d)
  3. Retry sequences are deterministic (T026d3)

### Example Log Output

**Seed 12345 (Placement Fails)**:
```text
[STRUCT] findSuitable: No valid placement found within radius=256 checked=1024 rejected=1024 seed=12345 retryHash=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6
```

**Seed 12345 (Repeated Run)** — Expected (identical hash):
```text
[STRUCT] findSuitable: No valid placement found within radius=256 checked=1024 rejected=1024 seed=12345 retryHash=a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6 ✅
```

**Seed 67890 (Different Seed)** — Expected (different hash):
```text
[STRUCT] findSuitable: No valid placement found within radius=256 checked=1024 rejected=1024 seed=67890 retryHash=9f8e7d6c5b4a39281726d5e4f3a2b1c0 ✅
```

### Known Limitations

🟡 **Manual Verification**:
- Automated harness parsing for retry hashes not yet implemented
- Requires manual log extraction and comparison via `Select-String`
- Future enhancement: Extend `test-path-determinism.ps1` to validate retry hashes

🟡 **Rare Retry Cases**:
- Most placements succeed on first few attempts (good terrain)
- To trigger retry hash logging, use seeds with constrained terrain (islands, dense forests, water-heavy areas)
- Consider adding test-only config flag to force placement failures for validation

### Enhancements implemented

- `test-path-determinism.ps1` now implements retry logic for Run 2 and deterministic diagnostics (T026d8): if Run 2 produces zero placements it will retry up to 2 times; if zero placements persist the script fails with an aggregated `ZERO-PLACEMENT` root-cause summary captured from logs and surfaced to CI artifacts.

**Notes:** the script still captures retry hashes and determinism hashes; future work could additionally validate retry-hash equality across runs.

---
[PATH] Determinism hash: f6e5d4c3b2a1... (nodes=38)  ✅
[PATH] Determinism hash: 123456789abc... (nodes=52)  ✅
```

**Seed 67890** — Expected (different):
```text
[PATH] Determinism hash: 9a8b7c6d5e4f... (nodes=42)  ✅
[PATH] Determinism hash: 6f5e4d3c2b1a... (nodes=40)  ✅
[PATH] Determinism hash: cba987654321... (nodes=55)  ✅
```

## R010: Headless Proof-of-Reality Tests

**Added**: 2025-11-21
**Status**: ✅ Implemented
**Location**: `scripts/ci/sim/run-scenario.ps1`

### What R010 Tests

Validates that the persisted data model (VolumeMasks, PlacementReceipts) matches the in-game reality (blocks).

#### 1. AABB-vs-World Audit
Runs `/votest verify-persistence <villageId>` for each generated village.
- Samples 32 points **inside** each VolumeMask and asserts they are NON-AIR (structure integrity).
- Samples 32 points **just outside** each VolumeMask and asserts they are NOT inside the mask (boundary check).
- Checks all path blocks to ensure they are NOT inside any VolumeMask (path integrity).

**Acceptance Criteria**:
- ✅ CI fails if any check fails (AIR inside mask, or path inside mask).
- ✅ Outputs coordinate list for reproduction.

### Example Output

```
=== R010: Headless Proof-of-Reality Verification ===
Verifying village 12345678-1234-1234-1234-123456789abc ...
  Response: PASS: All persistence checks passed (156 checks)
  OK Persistence verification passed
OK All 1 village(s) passed verification
```

---

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

## Manual Validation Checklist (R011)

Purpose: one-page operator checklist to validate PlacementReceipts and VolumeMasks against in-world reality using the `/votest verify-persistence` command.

Quick steps:

```powershell
# Run verification for a specific village via RCON
Send-RconCommand -Password "test123" -Command "/votest verify-persistence <villageId>" | Out-File test-server\logs\verify-<villageId>.log

# Or run in-game as an operator:
# /votest verify-persistence <villageId>
```

### Output Interpretation (R011c)

The `/votest verify-persistence` command provides concise diagnostics with categorized failure types:

**Per-Structure Summary Format:**
```text
Structure <id>: PASS|WARN|FAIL (corners=N, perimeter=N, outside=N)
```

**Status Classifications:**
- **PASS** (§a green): All checks passed for this structure
- **WARN** (§e yellow): Single AIR corner detected (acceptable edge case)
- **FAIL** (§c red): Multiple AIR corners, perimeter defects, or boundary violations

**Final Summary Format:**
```text
PASS: All persistence checks passed (N checks, M structures)
 OR
FAIL: X/Y checks failed (corner=N, perimeter=N, outside-mask=N, path=N)
```

**Failure Categories:**
- `corner`: Foundation corners (y=minY) at structure AABB corners
- `perimeter`: Foundation perimeter samples along AABB edges (y=minY)
- `outside-mask`: Points just outside mask boundaries incorrectly contained
- `path`: Path blocks incorrectly placed inside structure volumes

### WARN vs FAIL Guidance

**WARN (acceptable, does NOT cause test failure):**
- **Single AIR corner (1/4)**: Rotated structures may overhang terrain edges at cliff/slope boundaries
  - Example: `Structure market_roman_stall: WARN (corners=1, perimeter=0, outside=0)`
  - Action: Visual inspection recommended; verify walls are grounded and interior is stable
  - Edge cases: Natural terrain transitions, river banks, hillside placements

**FAIL (critical, MUST fix):**
- **Multiple AIR corners (2+/4)**: Indicates structural instability or terraforming failure
  - Example: `Structure villa_romana: FAIL (corners=3, perimeter=0, outside=0)`
  - Root cause: Inadequate foundation preparation or rotation-aware footprint mismatch
  - Action: Check `[STRUCT][RECEIPT]` logs for placement coordinates; verify terraforming aligned with rotated AABB

- **Perimeter defects**: Foundation edges have AIR blocks where solid ground expected
  - Example: `Structure forum: FAIL (corners=0, perimeter=5, outside=0)`
  - Root cause: Site preparation missed edge cases or gap-filling failed
  - Action: Review TerraformingUtil logs; verify `prepareSiteWithBounds()` operated on correct AABB

- **Boundary violations**: Points outside mask incorrectly contained
  - Example: `Structure bathhouse: FAIL (corners=0, perimeter=0, outside=12)`
  - Root cause: VolumeMask bounds mismatch with PlacementReceipt AABB
  - Action: Check VolumeMask creation logic; verify `fromReceipt()` uses identical bounds

- **Path intersections**: Path blocks placed inside structure volumes
  - Example: Final summary shows `path=8`
  - Root cause: Pathfinding did not respect VolumeMask obstacles or SurfaceSolver failed to exclude structure blocks
  - Action: Review PathServiceImpl obstacle detection; verify WalkableGraph construction excludes masked volumes

### Checklist (operator)

- [ ] Obtain the village id from generation logs (`[STRUCT] User-triggered village generation:` or harness output)
- [ ] Run `/votest verify-persistence <villageId>` and capture the resulting log (`test-server\logs\verify-<villageId>.log`)
- [ ] Review per-structure summary lines:
  - [ ] All PASS → proceed to final summary
  - [ ] WARN (single corner only) → note in playtest report, visual inspection recommended
  - [ ] FAIL (multiple corners, perimeter, outside, or path) → critical defect, stop and report
- [ ] Review final summary:
  - [ ] PASS → mark checklist complete, archive verification log
  - [ ] FAIL → collect artifacts (see below) and create ticket
- [ ] If WARN or FAIL, inspect `[STRUCT][RECEIPT]` lines in `test-server/logs/server.log` for bounds and corner samples
- [ ] In-game / via RCON verify particle markers at AABB corners and entrance coordinates reported by the command
- [ ] If any critical failure (FAIL status), capture these artifacts and include with bug report:
  - `test-server/logs/verify-<villageId>.log` (verify command output)
  - `test-server/logs/server.log` (plugin logs including `[STRUCT][RECEIPT]` and `[PATH]` lines)
  - `plugin/config/metadata-store.json` or `test-server/plugins/VillageMetadataStore.json` (persisted receipts/volumemasks if present)
  - Screenshot(s) of in-game markers and failing coordinates
  - Short reproduction note: seed used, command sequence, run timestamp, and any `ZERO-PLACEMENT` or `[STRUCT][DIAG]` lines if present

### Known Benign Edge Cases (WARN tolerances)

- **Single AIR corner at terrain edge** (1/4 corners): Structure may extend slightly beyond solid ground at cliff/slope boundaries after rotation. This is acceptable if:
  - Only ONE corner is AIR (3/4 solid)
  - Per-structure summary shows `WARN` (not `FAIL`)
  - Visual inspection confirms structure is stable (not floating, walls grounded)
  - Example: `market_roman_stall` at (-16,67,-104) with NE corner (1,67,-117)=AIR is acceptable if walls/interior are solid
- **Action**: Screenshot the placement, verify walls are grounded, note in artifact that 1 corner overhang is expected for rotated structures near terrain transitions

### Reporting guideline

- **If PASS (with or without single-corner WARN)**: Mark checklist complete and archive `verify-<villageId>.log` alongside run artifacts.
- **If FAIL (multiple AIR corners indicated by "corner=2" or higher)**: Create a ticket with the artifacts above, include the exact failure coordinates and the matching `[STRUCT][RECEIPT]` line, and tag `worldgen` and `qa`.
- **If FAIL (path inside structure or points outside mask are inside)**: Critical violation, create ticket immediately with `priority:P0` tag.

### Quick Reference Commands

Extract receipts from logs:
```powershell
Select-String '\[STRUCT\]\[RECEIPT\]' test-server\logs\server.log
```

Parse verification results:
```powershell
Select-String '(PASS|FAIL|WARN):' test-server\logs\verify-<villageId>.log
```

### Notes

- Use `Select-String '\[STRUCT\]\[RECEIPT\]' test-server\logs\server.log` to extract receipts quickly.
- The harness (`scripts/ci/sim/run-scenario.ps1`) will automatically invoke `/votest verify-persistence` when R010 is enabled; use this checklist for manual playtests and post-failure triage.
- Single-corner WARNs do NOT cause CI test failure; only multi-corner FAILs or perimeter/boundary/path defects trigger test failures.
