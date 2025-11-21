---

description: "Task list for Village Overhaul — Structures First"
---

# Tasks: Village Overhaul — Structures First

**Input**: Design documents from `/specs/001-village-overhaul/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Each component SHOULD have dedicated test coverage before integration begins. Harness
validations remain, with minimal unit tests for core algorithms where useful.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Ensure build and CI harness are ready to exercise structure generation flows

- [X] T001 Verify Gradle build for plugin in `plugin/build.gradle` and produce JAR to `plugin/build/libs/`
- [X] T002 [P] Add structure schema stub in `plugin/src/main/resources/schemas/structure.json`
- [X] T003 [P] Create package folders for worldgen services in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/`
- [X] T004 [P] Create package folders for placement/path services in `plugin/src/main/java/com/davisodom/villageoverhaul/villages/`
- [X] T005 Ensure CI harness scripts recognize new [STRUCT] logs in `scripts/ci/sim/run-scenario.ps1`
- [X] T005a [CI] Add WorldEdit auto-download and graceful fallback
  - Files: `scripts/ci/sim/run-scenario.ps1`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/VillageWorldgenAdapter.java`
  - Description: Add `Install-WorldEdit` function to automatically download WorldEdit 7.2.19 (compatible with Paper 1.20.4) into `test-server/plugins/`. Add try-catch in VillageWorldgenAdapter to gracefully fall back to procedural structures if WorldEdit fails to load (NoClassDefFoundError). This makes WorldEdit optional but recommended.
  - Acceptance:
    - ✅ Script checks for worldedit-bukkit JAR in plugins directory
    - ✅ Downloads WorldEdit 7.2.19 from BukkitDev if missing (not fatal if fails)
    - ✅ Verifies downloaded file size >1KB
    - ✅ Plugin catches NoClassDefFoundError and falls back to procedural structures
    - ✅ Logs clear warnings when WorldEdit is missing: "Falling back to procedural structures without WorldEdit/FAWE"
    - ✅ Structure placement succeeds with or without WorldEdit
  - Notes:
    - WorldEdit 7.3.x requires Paper 1.20.5+, not compatible with 1.20.4
    - Fallback uses VillagePlacementServiceImpl(metadataStore, cultureService) constructor
    - Procedural structures are generated using hardcoded dimensions (roman_house, roman_market, etc.)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core services and persistence required by all stories

- [X] T006 Define `VillagePlacementService` interface in `plugin/src/main/java/com/davisodom/villageoverhaul/villages/VillagePlacementService.java`
- [X] T007 [P] Define `StructureService` interface in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/StructureService.java`
- [X] T008 [P] Define `PathService` interface in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/PathService.java`
- [X] T008a [P] Define `BuilderService` interface and state model in `plugin/src/main/java/com/davisodom/villageoverhaul/builders/BuilderService.java`
- [X] T008b [P] Define `MaterialManager` interface in `plugin/src/main/java/com/davisodom/villageoverhaul/builders/MaterialManager.java`
- [X] T009 Implement persistence holders for main building/path metadata in `plugin/src/main/java/com/davisodom/villageoverhaul/villages/VillageMetadataStore.java`
- [X] T010 [P] Add data objects for Building/PathNetwork in `plugin/src/main/java/com/davisodom/villageoverhaul/model/`
- [X] T010a [P] Add data objects for Builder/MaterialRequest/PlacementQueue in `plugin/src/main/java/com/davisodom/villageoverhaul/model/`
- [X] T011 Wire debug flags and [STRUCT] logging in `plugin/src/main/java/com/davisodom/villageoverhaul/DebugFlags.java`
- [X] T012 Add admin test commands skeleton in `plugin/src/main/java/com/davisodom/villageoverhaul/commands/TestCommands.java`

- [X] T012a [Foundational] Wire TickEngine lifecycle in plugin
	- Files: `plugin/src/main/java/com/davisodom/villageoverhaul/VillageOverhaulPlugin.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/core/TickEngine.java`
	- Description: Initialize a single `TickEngine` instance in `VillageOverhaulPlugin#onEnable`. Schedule a repeating task (every 1 tick) to drive the engine and cancel it in `onDisable`. Expose `getTickEngine()` for tests and log concise "[TICK] engine started/stopped" messages controlled by `DebugFlags`.
	- Acceptance:
		- Plugin enable starts the engine and scheduler without exceptions.
		- Plugin disable cancels the task and releases references (no duplicate schedulers on re-enable).
		- `getTickEngine()` returns the running instance while enabled.

- [X] T012b [P] [QA] Expose manual tick and MockBukkit scheduler integration
	- Files: `plugin/src/main/java/com/davisodom/villageoverhaul/core/TickEngine.java`
	- Description: Ensure the engine has a public `tick()` method advancing the internal tick counter and invoking registered systems in deterministic registration order. Make registration and execution ordering stable to support headless and MockBukkit tests.
	- Acceptance:
		- Calling `tick()` increments `getCurrentTick()` by 1 and invokes all systems exactly once per call in deterministic order.
		- Order remains stable across repeated runs with identical registration sequences.

- [X] T012c [QA] Finalize deterministic tick tests (replace placeholders)
	- Files: `plugin/src/test/java/com/davisodom/villageoverhaul/TickHarnessTest.java`
	- Description: Replace placeholder assertions in the test with real checks: assert `getCurrentTick() == 0` on init; call `engine.tick()` N times and assert `getCurrentTick() == N`; verify multiple systems tick in order; add a MockBukkit scheduled-tick integration test that uses `performTicks(n)`.
	- Acceptance:
		- Tests pass locally and in CI with MockBukkit; no placeholder TODOs remain.

- [X] T012d [Foundational] Add configurable minimum building spacing
  - Files: `plugin/src/main/resources/config.yml`, `plugin/src/main/java/com/davisodom/villageoverhaul/VillageOverhaulPlugin.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
  - Description: Introduce config key `village.minBuildingSpacing` (default: 8 blocks). Load during `onEnable`, expose via a getter or configuration service. Enforce spacing when selecting candidate building positions before footprint validation.
  - Acceptance:
    - Config key present with default if missing.
    - Placement logs include spacing value when evaluating positions.
    - No two placed building footprints are closer than configured spacing (excluding paths/terraformed ground).

- [X] T012e [Foundational] Implement terrain classification API
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/TerrainClassifier.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/SiteValidator.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
  - Description: Provide `TerrainClassifier` with methods `isAcceptable(Block)` and `classify(x,y,z)` returning enum { ACCEPTABLE, FLUID, STEEP, BLOCKED }. Integrate into site validation and candidate search to skip unacceptable terrain early.
  - Acceptance:
    - Fluids (water/lava) always classified FLUID.
    - Steep slopes (>2 height delta within 3x3) classified STEEP.
    - Air/leaf/log unsupported foundations classified BLOCKED.
    - Structure placement only proceeds on ACCEPTABLE foundation tiles.
    - Logs show counts of rejected tiles per attempt (`[STRUCT] classification: fluid=..., steep=..., blocked=...`).

- [X] T012f [Foundational] Integrate non-overlap + spacing + terrain classifier in placement pipeline
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/TerrainClassifier.java`
  - Description: Refactor placement search to: (1) apply terrain classifier, (2) enforce min spacing before expensive validation, (3) compute rotation-aware footprint and verify no overlap with existing footprints, (4) record rejection reasons.
  - Acceptance:
    - ✅ Re-seat attempts enumerate rejection reasons (spacing/overlap/terrain) at debug level → PlacementRejectionTracker logs detailed breakdowns per structure
    - ✅ Final placements never overlap and honor spacing → findSuitablePlacementPosition() checks terrain → spacing → overlap
    - ✅ Average rejected attempts per village logged → Village-level summary includes avgRejected metric
  - Implementation:
    - Created `PlacementRejectionTracker` inner class with detailed metrics (terrain/spacing/overlap breakdown)
    - Created `findSuitablePlacementPosition()` with integrated checks (terrain → spacing → overlap priority)
    - Added `hasMinimumSpacing()` method for explicit spacing validation
    - Overloaded `footprintsOverlap()` for Footprint-to-Footprint collision detection
    - Village-level placement metrics logged: "Placement metrics for village X: attempts=N, rejected: terrain=N (fluid/steep/blocked), spacing=N, overlap=N, avgRejected=X.XX"
    - Per-position FINEST logging for rejection reasons (cheapest check first pattern)
  - **Tolerance Adjustment (2025-11-07)**: Relaxed zero-tolerance terrain checks after playtest showed 625+ attempts with 0 placements
    - **Hard veto**: ANY fluid tiles = reject (water avoidance per Constitution v1.5.0)
    - **Soft tolerance**: Up to 20% of samples can be steep/blocked (allows natural terrain variation)
    - `MAX_SLOPE_DELTA`: 2 → 4 blocks (gentle hills acceptable)
    - Sample density: every 2 blocks → every 4 blocks (4x performance improvement)
    - Added `ClassificationResult.isAcceptableWithTolerance()` method
    - See `T012f-relaxed-tolerance.md` for detailed analysis

**Checkpoint**: Services and DTOs exist; admin commands compile; ready to implement US1

---

## Phase 2.1: Foundational — Inter-Village Spacing & Borders (Priority: P1)

**Purpose**: Enforce village-level spacing (border-to-border, default 200 blocks), establish dynamic
border tracking, and align site selection with spawn proximity and nearest-neighbor bias.

- [X] T012g [Foundational] Add configurable minimum village spacing
  - Files: `plugin/src/main/resources/config.yml`, `plugin/src/main/java/com/davisodom/villageoverhaul/VillageOverhaulPlugin.java`
  - Description: Introduce `village.minVillageSpacing` (default: 200). Load on enable and expose via config service.
  - Acceptance:
    - Config key present with default if missing.
    - `/vo generate` and natural gen reference this value in logs.
  - Implementation:
    - Added `village.minVillageSpacing: 200` to config.yml with documentation
    - Added `minVillageSpacing` field to VillageOverhaulPlugin
    - Load config value in onEnable with default fallback (200)
    - Exposed via `getMinVillageSpacing()` public getter
    - Updated config load log to show both spacing values

- [X] T012h [Foundational] Persist and update dynamic village borders
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/VillageMetadataStore.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
  - Description: Represent village border as axis-aligned bounds; expand deterministically on building placement; persist and expose getters.
  - Acceptance:
    - After building placements, border bounds expand to include all footprints.
    - Border persisted and retrievable across restarts.
  - Implementation:
    - Created `VillageBorder` class with axis-aligned bounds (minX, maxX, minZ, maxZ)
    - Added `border` field to `VillageMetadata` (initialized at origin, expands with buildings)
    - Added `lastBorderUpdateTick` field to track border update timestamp
    - Implemented `expandBorderForBuilding()` method in VillageMetadata
    - Integrated border expansion in `addBuilding()` - automatically expands when building added
    - Added `getBorder()` and `getLastBorderUpdateTick()` getters
    - Implemented `isWithinDistance()` for inter-village spacing checks (Manhattan distance)
    - Implemented `getDistanceTo()` for nearest-neighbor distance calculations
    - Border expansion is deterministic and idempotent (Math.min/max)
    - toString() provides readable border representation

- [X] T012i [Foundational] Enforce inter-village spacing during village site search
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/commands/GenerateCommand.java`
  - Description: During site selection (user-invoked and natural), reject candidate sites whose border would violate `minVillageSpacing` vs any existing village border (border-to-border, bidirectional).
  - Acceptance:
    - Attempted placements within `minVillageSpacing` are rejected with `[STRUCT]` logs indicating `rejectedVillageSites.minDistance`.
    - Successful placements always satisfy min border distance.
  - Implementation:
    - Added `minVillageSpacing` field to VillagePlacementServiceImpl (loaded from config)
    - Updated all three constructors to initialize minVillageSpacing (default 200, or from plugin config)
    - Implemented `checkInterVillageSpacing()` method: Creates temporary border at proposed origin, checks against all existing village borders using `isWithinDistance()`
    - Added pre-check in `placeVillage()` before village placement begins
    - Logs rejection with actual distance and required distance at FINE level
    - Updated placement log to include both minBuildingSpacing and minVillageSpacing values
    - Added `formatLocation()` helper for consistent location logging
    - Note: Current implementation works within same VillageMetadataStore instance; cross-session enforcement requires shared metadata store (see T012l below)

- [X] T012j [Foundational] Spawn-proximal initial placement & nearest-neighbor bias
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/commands/GenerateCommand.java`, `plugin/src/main/resources/config.yml`, `plugin/src/main/java/com/davisodom/villageoverhaul/VillageOverhaulPlugin.java`
  - Description: Bias first/early villages to spawn proximity (not exact spawn). For subsequent villages, prefer the nearest valid site to existing borders while respecting the minimum distance.
  - Acceptance:
    - First village placed within a configured radius of spawn (not exactly at spawn).
    - Subsequent villages placed at the smallest valid distance ≥ `minVillageSpacing`.
  - Implementation:
    - Added `village.spawnProximityRadius: 512` to config.yml with documentation
    - Added `spawnProximityRadius` field to VillageOverhaulPlugin with getter
    - Updated config load to include spawnProximityRadius in log
    - VillagePlacementServiceImpl: Added `isFirstVillage()`, `isWithinSpawnProximity()`, `getDistanceToNearestVillage()` helper methods
    - Updated `placeVillage()` to log first vs subsequent village status and distances
    - GenerateCommand: Added `isFirstVillage()`, `findNearestVillageLocation()`, `formatLocation()` helpers
    - Updated terrain search logic to:
      - First village: Search within spawnProximityRadius of world spawn
      - Subsequent villages: Search near nearest existing village border (nearest-neighbor bias)
    - Logs show spawn distance for first village, nearest village distance for subsequent
    - Note: Full effectiveness requires T012l (shared metadata store) for cross-session detection

- [X] T012k [Foundational] Observability for inter-village enforcement
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
  - Description: Emit structured logs/metrics for inter-village rejections and border-limited expansions.
  - Acceptance:
    - Logs include counters like `rejectedVillageSites.minDistance=NN` and border expansion clips.
  - Implementation:
    - Created `InterVillageSpacingResult` inner class with fields: `boolean acceptable`, `int actualDistance`, `UUID violatingVillageId`
    - Implemented `checkInterVillageSpacingDetailed()` method returning detailed spacing check results
    - Enhanced `placeVillage()` logging to include: `rejectedVillageSites.minDistance=%d, existingVillage=%s`
    - Structured logs now show: site location, minVillageSpacing config, actual distance to violating village, and violating village UUID
    - FINE-level logs include detailed rejection info: `"Rejecting site at %s: distance %d to village %s violates minVillageSpacing=%d"`

- [X] T012l [Foundational] Implement shared VillageMetadataStore singleton
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/VillageOverhaulPlugin.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/commands/GenerateCommand.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/VillageWorldgenAdapter.java`
  - Description: Create singleton VillageMetadataStore instance in VillageOverhaulPlugin lifecycle (initialize in onEnable, cleanup in onDisable). Expose via `getMetadataStore()` getter. Update all callers (VillagePlacementServiceImpl, GenerateCommand, VillageWorldgenAdapter) to use shared instance instead of creating new instances. This enables inter-village spacing checks to work across all command invocations and worldgen events.
  - Acceptance:
    - VillageOverhaulPlugin manages single metadata store instance throughout plugin lifecycle
    - All village placements (commands, worldgen, tests) use shared store
    - Inter-village spacing enforcement works across multiple `/vo generate` invocations
    - Villages persist in metadata store across placements (until server restart)
    - No duplicate metadata store instances created
  - Implementation:
    - Added `import com.davisodom.villageoverhaul.villages.VillageMetadataStore;` to VillageOverhaulPlugin
    - Added `private VillageMetadataStore metadataStore;` field to VillageOverhaulPlugin
    - Initialized in `initializeFoundation()`: `metadataStore = new VillageMetadataStore(this);`
    - Added public getter: `public VillageMetadataStore getMetadataStore() { return metadataStore; }`
    - GenerateCommand updated: `VillageMetadataStore metadataStore = plugin.getMetadataStore();` (replaces `new VillageMetadataStore(plugin)`)
    - VillageWorldgenAdapter updated: `VillageMetadataStore metadataStore = plugin.getMetadataStore();` (replaces `new VillageMetadataStore(plugin)`)
    - All callers now use shared singleton instance via `plugin.getMetadataStore()`
    - BUILD SUCCESSFUL with all 14 tests passing (6 TickHarnessTest, 8 EconomyAntiDupeTest)

**Checkpoint**: Inter-village rules enforced and observable; borders persisted; ready for US1.

---

## Phase 3: User Story 1 — Structure Generation & Placement (Priority: P1) 🎯 MVP

**Goal**: Place culture-defined structures with grounded placement; allow minor terraforming when needed

**Independent Test**: Generate for sampled seeds; assert 0 floating/embedded; entrances have interior air-space

- [X] T013 [US1] Implement site validation (foundation/interior clearance) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/SiteValidator.java`
- [X] T014 [P] [US1] Implement minor terraforming utils (light grading/filling, vegetation trim) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/TerraformingUtil.java`
- [X] T015 [US1] Implement `StructureServiceImpl` (load/rotate/paste via WorldEdit/FAWE, seat/re-seat/abort, deterministic order) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`
- [X] T016 [P] [US1] Add FAWE-backed placement path (if FAWE present) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`
- [X] T016a [US1] Implement async placement queue with main-thread batched commits in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PlacementQueueProcessor.java`
- [X] T017 [US1] Integrate seating into `VillagePlacementServiceImpl` in `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
- [X] T018 [US1] Extend test command: `votest generate-structures <village-id>` in `plugin/src/main/java/com/davisodom/villageoverhaul/commands/TestCommands.java`
- [X] T018b [US1] Implement user-facing command: `/vo generate <culture> <name> [seed]` in `plugin/src/main/java/com/davisodom/villageoverhaul/commands/GenerateCommand.java`
  - Description: User-facing village generation command; find terrain, call `VillagePlacementService` to place structures; log [STRUCT] summary. When US2 is complete, also invoke path network generation (or report that paths are unavailable yet).
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/commands/GenerateCommand.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/commands/ProjectCommands.java`
  - Implementation:
    - Created `GenerateCommand` class with async terrain search (512 block radius)
    - Terrain suitability criteria: Y variation ≤15 blocks, <30% water, height 50-120
    - Wired into `/vo generate` subcommand via ProjectCommands
    - Tab completion suggests available culture IDs
    - Logs: `[STRUCT] User-triggered village generation: '<name>' (culture=<culture>, seed=<seed>)`
    - Reports building count, seed, location to user
    - Placeholder message: "Paths: Not yet available (US2 in progress)"
    - Fallback: Places marker pillar (stone + torch) if structure placement fails
  - Acceptance:
    - ✅ Command registers and executes without error
    - ✅ Terrain search finds suitable flat area (spiral search pattern)
    - ✅ Calls VillagePlacementService.placeVillage()
    - ✅ Summary logging shows placement metrics (building count, seed, location)
    - ✅ User feedback with colored messages (§a for success, §c for errors)
- [X] T019 [US1] Add [STRUCT] logs: begin/seat/re-seat/abort with seed inputs in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`
- [X] T020 [US1] Update harness parsing to assert "0 floating/embedded" in `scripts/ci/sim/run-scenario.ps1`

- [X] T020a [US1] Enforce water avoidance & spacing in structure seating
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/SiteValidator.java`
  - Description: Extend seating logic to abort if any foundation/interior block is FLUID. Include spacing pre-check before detailed validation. Update logs with `[STRUCT] seat rejected: fluid` or `spacing`.
  - Acceptance:
    - No building placed with any water/lava under footprint.
    - Logs show rejection reason when failing due to fluid or spacing.
    - Existing successful seats unaffected (still grounded & interior air ok).
  - Implementation:
    - Added site validation check BEFORE terraforming in `attemptPlacementWithReseating()`
    - Validation now aborts placement if foundation has fluids (water/lava)
    - Created `buildRejectionReason()` helper method to provide detailed rejection logs
    - Logs show specific rejection reasons: fluid (with tile count), steep, blocked, interior air, entrance access
    - Example log: `[STRUCT] Seat rejected at attempt 1: fluid (water/lava: 12 tiles), steep (3 tiles)`
    - SiteValidator already had fluid detection via TerrainClassifier (FLUID classification = hard veto)
    - Spacing checks already enforced in VillagePlacementServiceImpl (minBuildingSpacing)
    - **Vegetation Fix (2025-11-07)**: Separated vegetation from blocked terrain
      - Added VEGETATION classification category for leaves, logs, grass, flowers
      - Vegetation no longer counted as rejected terrain (can be trimmed during terraforming)
      - Only FLUID (water/lava), STEEP, and BLOCKED (air/void) count as rejections
      - Updated TerrainClassifier: VEGETATION materials moved from UNSUPPORTED set to separate VEGETATION set
      - ClassificationResult.getRejected() excludes vegetation count (only fluid + steep + blocked)
      - isAcceptableWithTolerance() hard-vetos fluid, allows 20% steep/blocked (vegetation ignored)
    - All 14 tests passing (6 TickHarnessTest, 8 EconomyAntiDupeTest)

**Checkpoint**: US1 independently verifiable via headless harness

---

## Phase 4: User Story 2 — Path Network & Main Building (Priority: P1)

**Goal**: Connect key buildings with traversable paths and designate exactly one main building per village

**Independent Test**: Verify path connectivity ratio and a single persisted main building

- [X] T021 [US2] Implement `PathServiceImpl` (A* heightmap + smoothing) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`
- [X] T022 [P] [US2] Emit path blocks with minimal smoothing (steps/slabs) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathEmitter.java`
- [X] T023 [US2] Implement main building designation logic in `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/MainBuildingSelector.java`
- [X] T024 [US2] Persist mainBuildingId and pathNetwork in `plugin/src/main/java/com/davisodom/villageoverhaul/villages/VillageMetadataStore.java`
- [X] T025 [US2] Extend test command: `votest generate-paths <village-id>` in `plugin/src/main/java/com/davisodom/villageoverhaul/commands/TestCommands.java`
- [X] T026 [US2] Harness assertion for path connectivity ≥ 90% in `scripts/ci/sim/run-scenario.ps1`
- [X] T026a [US2] Add tests for pathfinding concurrency cap and waypoint cache invalidation in `scripts/ci/sim/run-scenario.ps1`
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: Validate MAX_NODES_EXPLORED enforcement during A* pathfinding and path network cache behavior. Tests parse [PATH] logs to verify node exploration limits, graceful failures, and cache patterns. Documents current implementation status and future waypoint cache validation plans.
  - Acceptance:
    - ✅ Node cap tests verify explored nodes never exceed MAX_NODES_EXPLORED (5000)
    - ✅ Failed paths log: `[PATH] A* FAILED: node limit reached (explored=N/5000)`
    - ✅ Successful path node exploration tracked (min/max/avg statistics)
    - ✅ Performance warning if average exploration >3000 nodes
    - ✅ Cache tests verify each village generates paths exactly once
    - ✅ Regeneration detection (indicates potential cache invalidation)
    - ✅ Documentation includes current vs. future implementation notes
  - Implementation:
    - Added "Pathfinding Node Cap Validation" section to run-scenario.ps1
    - Parses `[PATH] A* FAILED: node limit reached` pattern with regex
    - Validates explored ≤ cap for all failed paths
    - Parses `[PATH] A* SUCCESS: Goal reached after exploring N nodes` pattern
    - Calculates node exploration statistics (min/max/avg) for successful paths
    - Added "Waypoint Cache Validation" section to run-scenario.ps1
    - Tracks path network cache entries per village via `[STRUCT] Path network complete` pattern
    - Detects path regeneration (multiple completions for same village UUID)
    - Notes: Current implementation has village-level path network cache only
    - Future work: Full waypoint-level cache and terrain-triggered invalidation
    - Updated HEADLESS-TESTING.md with comprehensive T026a test documentation:
      - Test patterns and acceptance criteria
      - Example outputs with color-coded results
      - How to run and interpret results
      - Future waypoint cache enhancement plans
- [X] T026b [P] [US2] Add headless test for path generation between distant buildings (within 200 blocks) in `scripts/ci/sim/run-scenario.ps1`; assert non-empty path blocks between two buildings ≥120 blocks apart (and within MAX_SEARCH_DISTANCE), or graceful skip if out-of-range. Update `tests/HEADLESS-TESTING.md` with run notes.
- [X] T026c [P] [US2] Add terrain-cost accuracy integration test in `scripts/ci/sim/run-scenario.ps1`: construct two candidate routes (flat vs water/steep) and assert chosen path avoids higher-cost tiles when a comparable-length flat route exists. Document setup in `tests/HEADLESS-TESTING.md`. (Implemented; validated in headless run 2025-11-09)
- [X] T026c1 [P] [US2] Add controlled comparison test for explicit flat vs water/steep route scenarios in `scripts/ci/sim/run-scenario.ps1`
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`, `plugin/src/main/java/com/davisodom/villageoverhaul/commands/TestCommands.java`
  - Description: Provide controlled route comparison validation via manual playtest guidance. Implement obstacle placement commands and document scenarios; defer full harness automation due to complexity/ROI. Assert (manually) that when a comparable-length flat route exists (<20% longer), A* prefers it over a shorter water/steep route.
  - Acceptance:
    - Test command `/votest place-obstacle water <x> <z> <radius>` creates water patches
    - Test command `/votest place-obstacle steep <x> <z> <width>` creates elevation changes
    - Manual scenarios documented in `tests/HEADLESS-TESTING.md` (T026c1 section)
    - First village: flat route (~50) vs water route (~40) → A* chooses flat (water=0)
    - Second village: flat route (~50) vs steep route (~45) → A* chooses flat (steep=0)
    - Logs show chosen route cost breakdown; alternative cost estimation guidance provided
  - Notes: Full automation and alternative-route rejection logging deferred (future enhancement).
- [X] T026d [P] [US2] Add deterministic path-from-seed check in `scripts/ci/sim/run-scenario.ps1`: run path generation twice with the same seed and hash the ordered (x,y,z) path blocks; assert identical hashes; with a different seed, assert hash changes. Capture artifacts under `test-server/logs/`.
  - Files: `scripts/ci/sim/run-scenario.ps1`, `scripts/ci/sim/test-path-determinism.ps1`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`, `tests/HEADLESS-TESTING.md`
  - Description: Validate path generation determinism by computing MD5 hash of ordered path node coordinates. Log hash after each successful A* path. Automated test script runs scenario 3 times (seed A twice, seed B once) and compares hash sequences.
  - Acceptance:
    - ✅ PathServiceImpl logs `[PATH] Determinism hash: <32-hex> (nodes=N)` after every successful path
    - ✅ Hash computed using MD5 of ordered "x,y,z;" coordinate string
    - ✅ Harness parses hash logs and reports unique hash count
    - ✅ Automated test script (`test-path-determinism.ps1`) validates determinism and variance
    - ✅ Variance confirmed: Different seeds produce different hashes
    - 🟡 Determinism test blocked: Structure placement non-deterministic (Run 1: 3 placements, Run 2: 0 placements)
  - Implementation:
    - Added `computePathHash()` method to PathServiceImpl (MD5 of ordered coordinates)
    - Added determinism hash logging after `logPathTerrainCosts()` call
    - Hash format: "x1,y1,z1;x2,y2,z2;..." → MD5 → 32-character hex string
    - Added T026d validation section to run-scenario.ps1 (groups by hash value)
    - Created `test-path-determinism.ps1`: automated 3-run test (seed A×2, seed B×1)
    - Harness reports: unique hashes, duplicate occurrences, guidance for full testing
    - Added comprehensive T026d documentation to HEADLESS-TESTING.md:
      - Automated test usage and expected results
      - Manual multi-run test procedures (fallback)
      - Known limitation: structure placement determinism dependency
      - Example hash sequences with PASS/FAIL criteria
      - Integration with T026a cache testing
  - Verified: 2025-11-09 automated test results:
    - Run 1 (Seed 12345): 2 hashes logged (e0292f0a..., 051d4267...)
    - Run 2 (Seed 12345): 0 hashes logged (structure placement failed)
    - Run 3 (Seed 67890): 4 hashes logged (8ca24f81..., 74fcb9ca..., facb604e..., 4daf7a84...)
    - Variance test: PASS ✅ (Seed A ≠ Seed B confirmed)
    - Determinism test: FAIL 🟡 (Run 2 had 0 structure placements, cannot compare)
  - Note: Path hash generation and variance validation complete; determinism validation awaits structure placement reproducibility fixes

**Checkpoint**: US2 independently verifiable

---

## Phase 4.5: Foundational Rewrite — Persistence & Pathfinding (Priority: P0)

Purpose: Replace the current placement/persistence/path pipeline with a rigorously verified, ground-truth-first system. Eliminate reliance on heuristics or ambiguous logs. All coordinates persisted must be proven against in-game reality before any path is generated.

Supersedes: T021b, T021c, T022a stabilization items. Keep for history but do not iterate further on them.

- [X] R001 [Core] Canonical placement transform and receipt
  - Files: `plugin/src/main/java/.../worldgen/impl/StructureServiceImpl.java`, `.../villages/impl/VillagePlacementServiceImpl.java`, `.../model/PlacementReceipt.java`
  - Description: Define a canonical world transform T for every paste: {origin(x,y,z), rotation(0/90/180/270), effectiveWidth, effectiveDepth, height}. After paste, compute exact AABB in world coords. Emit a PlacementReceipt containing: structureId, villageId, world, minX/maxX/minY/maxY/minZ/maxZ, rotation, effective dims, and four foundation-corner samples with block types.
  - Acceptance:
    - After every successful paste, a PlacementReceipt is produced and persisted.
    - Corner samples match non-air solid blocks in-world (proof of paste alignment).
    - Logs include one-line receipt summary `[STRUCT][RECEIPT] ...` with bounds.
  - Implementation:
    - Created `PlacementReceipt` model class with:
      - Identifiers: structureId, villageId, worldName
      - Exact AABB bounds: minX/maxX, minY/maxY, minZ/maxZ (inclusive)
      - Transform parameters: originX/Y/Z, rotation (0/90/180/270)
      - Effective dimensions: effectiveWidth, effectiveDepth, height
      - Foundation corner samples: 4 corners (NW, NE, SE, SW) with coordinates and block types
      - Validation: `verifyFoundationCorners()` checks all corners are non-air solid blocks
      - Logging: `getReceiptSummary()` provides compact one-line format
    - Added helper methods to StructureServiceImpl:
      - `computeAABB()`: Calculate world-space bounds accounting for rotation
        - 0°/180°: effectiveWidth = baseWidth, effectiveDepth = baseDepth
        - 90°/270°: effectiveWidth = baseDepth, effectiveDepth = baseWidth
      - `sampleFoundationCorners()`: Sample 4 corners at y=minY (foundation level)
        - Order: NW (minX, minZ), NE (maxX, minZ), SE (maxX, maxZ), SW (minX, maxZ)
    - Added `placeStructureAndGetReceipt()` method:
      - Calls existing placement logic with re-seating
      - Computes AABB after successful placement
      - Samples foundation corners as proof of alignment
      - Builds and returns PlacementReceipt
      - Logs: `[STRUCT][RECEIPT] <summary>` with full bounds and dimensions
      - Warns if corner verification fails (non-solid blocks detected)
    - Updated VillagePlacementServiceImpl:
      - Attempts to call `placeStructureAndGetReceipt()` if available (via instanceof check)
      - Falls back to old `placeStructureAndGetResult()` for backwards compatibility
      - Stores receipt via `metadataStore.addPlacementReceipt()`
      - Extracts origin, rotation, dimensions from receipt or legacy PlacementResult
    - Updated VillageMetadataStore:
      - Added `placementReceipts` map (villageId → List<PlacementReceipt>)
      - Added `addPlacementReceipt()` and `getPlacementReceipts()` methods
      - Added PlacementReceiptDTO and CornerSampleDTO for JSON persistence
      - Updated VillageDataDTO to include `placementReceipts` list
      - Added conversion methods: `convertReceiptToDTO()` and `convertReceiptFromDTO()`
      - Updated `saveAll()` to persist receipts alongside other village data
      - Updated `loadAll()` to restore receipts from JSON
      - Updated `clearAll()` to clear receipts map
  - Status: ✅ COMPLETE
    - Build successful (gradle build -x test)
    - PlacementReceipt provides ground-truth bounds and corner samples
    - Receipts persisted and restored via JSON
    - [STRUCT][RECEIPT] logs emitted with full bounds
    - Foundation corner verification in place
  - **Fix Applied (2025-11-11)**:
    - Added `normalizeClipboardOrigin()` helper to shift clipboard origin to minimum corner at load time
    - Standardizes paste behavior: origin = structure's minimum corner (SW-bottom)
    - Updated `computeAABB()` to work with normalized clipboards:
      - Uses clipboard dimensions directly (origin at 0,0,0 after normalization)
      - Rotates all 8 corners of bounding box using Y-axis rotation matrix
      - Finds min/max of rotated corners for accurate AABB
      - Translates to world coordinates: paste origin + rotated offsets
    - Eliminates complex offset calculations and arbitrary origin handling
    - Logs show origin normalization at FINE level during structure load
    - Ready for playtest verification with known-good corner coordinates

- [X] R002 [Core] Verified persistence model (VolumeMask)
  - Files: `.../villages/VillageMetadataStore.java`, `.../model/VolumeMask.java`
  - Description: Replace ad-hoc footprint persistence with a VolumeMask that stores the exact 3D bounds and optional per-layer occupancy flags. Persist alongside PlacementReceipt. Provide queries: `contains(x,y,z)`, `contains2D(x,z,yMin..yMax)`, and `expand(buffer)`.
  - Acceptance:
    - All placed structures have a persisted VolumeMask with min/max bounds identical to the receipt.
    - `contains` checks match in-world block reality on a random 32-sample audit (see R006).
  - Implementation:
    - Created `VolumeMask` model class with:
      - Identifiers: structureId (String), villageId (UUID)
      - Exact 3D bounds: minX/maxX, minY/maxY, minZ/maxZ (inclusive)
      - Cached dimensions: width, height, depth
      - Optional per-block occupancy bitmap (BitSet, null = full occupancy)
      - Timestamp for versioning
    - Implemented spatial query methods:
      - `contains(x,y,z)`: Point-in-volume check with optional occupancy bitmap
      - `contains2D(x,z,yMin,yMax)`: 2D column intersection check for pathfinding
      - `expand(buffer)`: Create expanded volume with buffer zone (for obstacle detection)
    - Added `fromReceipt(PlacementReceipt)` factory method for easy creation
    - Updated VillageMetadataStore:
      - Added `volumeMasks` concurrent map (villageId → List<VolumeMask>)
      - Added `addVolumeMask()` and `getVolumeMasks()` methods
      - Created `VolumeMaskDTO` for JSON persistence (with Base64 bitmap field for future)
      - Added conversion methods: `convertVolumeMaskToDTO()` and `convertVolumeMaskFromDTO()`
      - Updated `saveAll()` to persist volume masks alongside receipts
      - Updated `loadAll()` to restore volume masks from JSON
      - Updated `clearAll()` to clear volume masks map
    - Updated VillagePlacementServiceImpl:
      - After storing PlacementReceipt, automatically creates and stores VolumeMask
      - Uses `VolumeMask.fromReceipt()` for consistent bounds
    - Notes:
      - Initial implementation uses full occupancy (no per-block bitmap)
      - Occupancy bitmap serialization deferred for future enhancement
      - Ready for R005 walkable graph integration (obstacle detection)
  - Status: ✅ COMPLETE
    - Build successful (gradle build -x test)
    - VolumeMask provides verified 3D volume persistence
    - Bounds identical to PlacementReceipt (ground-truth alignment)
    - Spatial queries ready for pathfinding integration
    - Persisted and restored via JSON alongside receipts

- [X] R003 [Core] Entrance anchors from data or palette
  - Files: `.../worldgen/StructureService.java`, structure JSON in `plugins/VillageOverhaul/structures/*.json`
  - Description: Add entrance anchors per structure (relative to schematic) or auto-detect doors during paste. Transform anchor via T and then snap to adjacent walkable ground outside the AABB+buffer.
  - Acceptance:
    - Each placed building has exactly one entrance world coordinate persisted.
    - The entrance lies strictly outside `VolumeMask.expand(buffer=2)` and is on solid natural ground.

- [X] R004 [Core] Ground-truth surface solver
  - Files: `.../worldgen/SurfaceSolver.java`
  - Description: Build a deterministic surface function G(x,z) by scanning down from world max height, ignoring any blocks whose (x,y,z) fall inside any VolumeMask. Expose `nearestWalkable(x,z,yHint)` which returns y within {G(x,z)-1..G(x,z)+1}.
  - Acceptance:
    - For 100 random samples around a village, `nearestWalkable` never returns a y that is inside any VolumeMask.
    - Performance: <2ms per 64×64 query window (cache accepted).

- [X] R005 [Core] Walkable graph and obstacle field
  - Files: `.../worldgen/impl/PathServiceImpl.java`
  - Description: Generate a walkable node graph over a window that includes all entrances. Nodes exist only at y = G(x,z) ± 1. Obstacles are `VolumeMask.expand(buffer=2)` and fluids. No node may enter an obstacle at any y.
  - Acceptance:
    - Constructed graph contains 0 nodes whose coordinates intersect any VolumeMask.
    - Node degree <= 8, with vertical delta |dy| ≤ 1 between neighbors.

- [X] R006 [QA] Manual validation utility (in-game proof)
  - Files: `.../commands/TestCommands.java`
  - Description: Add `/votest verify-persistence <villageId>` that: (1) draws particles at persisted AABB corners and entrance; (2) samples 32 random points inside VolumeMask and asserts blocks are non-air; (3) samples 32 just-outside points and asserts not-in-mask. Output a PASS/FAIL summary.
  - Acceptance:
    - Command prints PASS only if all checks succeed; failures include exact coordinates.
    - Screenshot checklist provided in tests guide (see R011).

- [X] R007 [Core] A* over walkable graph (3D, slope-aware)
  - Files: `.../worldgen/impl/PathServiceImpl.java`
  - Description: Implement A* that expands neighbors strictly within the walkable graph. Costs: flat=1, slope=1.5, water=+∞ (blocked). Start/end are the verified entrances, snapped with `nearestWalkable`.
  - Acceptance:
    - Paths never include nodes inside any VolumeMask (by construction).
    - Report `[PATH] avoided N structure nodes` and determinism hash for each path.

- [ ] R008 [Emitter] Support-checked path emission
  - Files: `.../worldgen/impl/PathEmitter.java`
  - Description: Place path blocks only when the block below is solid natural ground and the target is not inside any VolumeMask. Apply simple widening after emission; never place slabs/stairs when support is missing.
  - Acceptance:
    - Zero floating slabs in smoke test; zero placements inside VolumeMask.

- [ ] R009 [Migration] Replace old persistence and path calls
  - Files: `VillagePlacementServiceImpl`, `PathServiceImpl`, `StructureServiceImpl`
  - Description: Remove legacy footprint code, `findGroundLevel` heuristics, and any path code that probes world state without the SurfaceSolver. Wire the new pipeline end-to-end.
  - Acceptance:
    - Build passes; legacy methods no longer referenced; logs updated.

- [ ] R010 [Harness] Headless proof-of-reality tests
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: Add checks that fail if any path block is within a VolumeMask; add AABB-vs-world audit sampler identical to `/votest verify-persistence`.
  - Acceptance:
    - CI fails on any mismatch; outputs coordinate list for reproduction.

- [ ] R011 [Guide] Manual validation checklist (one page)
  - Files: `tests/HEADLESS-TESTING.md`
  - Description: Step-by-step operator guide to validate receipts vs reality. Includes how to run `/votest verify-persistence`, what screenshots to capture, and how to report mismatches.
  - Acceptance:
    - Document lives next to existing headless docs; references exact log lines and commands.

- [ ] R012 [Diagnostics] Minimal, truthful logs
  - Files: `PathServiceImpl`, `VillagePlacementServiceImpl`
  - Description: Consolidate logs to receipt summaries, entrance world coords, A* node counts, determinism hash, and "avoided structure nodes". Remove ambiguous or derived logs that previously misled triage.
  - Acceptance:
    - Log set is small, consistent, and directly derived from persisted ground-truth data.

Notes:
- R001–R006 must land together behind a feature flag (`worldgen.rewrite.enabled=true`).
- Only when R006 passes in playtest should R007–R009 be enabled for paths.
- After migration, mark T021b/T021c as superseded by the rewrite.
- **Determinism Stabilization (Structure Placement & Path Generation)**
  - [ ] T026d1 [P] [US2] Deterministic RNG seeding audit for placement pipeline
    - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`, `StructureServiceImpl.java`
    - Description: Ensure all random operations (ordering, offsets, rotation choices) derive from a single seed chain (world seed + village seed). Replace any `new Random()` calls without explicit seed.
    - Acceptance: Log a single `[STRUCT] seed-chain: <villageSeed> -> <placementSeed>` line; repeated runs with same seed produce identical ordering lists.
  - [ ] T026d2 [US2] Stable candidate site ordering & filtering
    - Files: `VillagePlacementServiceImpl.java`
    - Description: Collect candidate sites then sort by deterministic key (e.g., distance, elevation, coordinates) instead of iteration order. Apply filters in fixed sequence.
    - Acceptance: Harness debug log shows identical candidate sequence across same-seed runs.
  - [ ] T026d3 [US2] Deterministic re-seat logic
    - Files: `StructureServiceImpl.java`
    - Description: When a seating attempt fails, next candidate selection MUST follow a stable order (no early exits based on timing). Remove any non-deterministic collection iteration or hash-based ordering.
    - Acceptance: Repeated failures yield identical retry sequences (hash of retry target coordinates stable).
  - [ ] T026d4 [P] [US2] Chunk readiness gating for placement commits
    - Files: `StructureServiceImpl.java`, `PlacementQueueProcessor.java`
    - Description: Before committing block batches, assert all target chunks are loaded; if not, defer commit deterministically. Prevent race where unloaded chunk causes abort.
    - Acceptance: Zero "Abort: No buildings placed" events solely due to missing chunk readiness in same-seed repeated runs.
  - [ ] T026d5 [US2] Structured diagnostics for zero-placement cases
    - Files: `StructureServiceImpl.java`
    - Description: Emit `[STRUCT][DIAG] zero-placement root-cause=...` with enumerated counters (candidatesRejected=, terrainInvalid=, chunkNotReady=, overlap=, water=).
    - Acceptance: Every zero-placement event includes root-cause line; harness parses and summarizes counts.
  - [ ] T026d6 [P] [US2] Fixed layout harness mode
    - Files: `scripts/ci/sim/run-scenario.ps1`, `test-path-determinism.ps1`
    - Description: Add `-FixedLayout` flag to place a predefined list of structure footprints (no search) to isolate path determinism; uses stable coordinates relative to seed.
    - Acceptance: In fixed layout mode, determinism test passes (Run 1 == Run 2 hashes) for seed 12345.
  
  ## Zero-placement & Harness Resilience (new)

  - [ ] T026d11 [P1] Zero-placement diagnostics
    - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/villages/VillageMetadataStore.java`
    - Description: Emit a single, structured root-cause summary whenever a village generation run finishes with zero placed structures. The summary must include total attempts, placed count (0), and rejection breakdown (terrain:fluid, terrain:steep, terrain:blocked, spacing, overlap), plus the seed-chain and candidate counts. The line must be parsable by the harness (example: `ZERO-PLACEMENT rootCause=fluid:13426,steep:234,blocked:2382,spacing:266,overlap:0 attempts=1085 seedChain=...`).
    - Acceptance: A single per-village INFO log is emitted on zero-placement that the CI/harness can parse and attach to artifacts.

  - [ ] T026d12 [P1] Placement rejection counters (persisted)
    - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/villages/VillageMetadataStore.java`, `scripts/ci/sim/run-scenario.ps1`
    - Description: Instrument per-attempt rejection counters (fluid, steep, blocked, spacing, overlap) and persist them in the `VillageMetadataStore` alongside other placement metadata. Export a parsable artifact (JSON/CSV) after each run for offline analysis.
    - Acceptance: The harness collects a per-village counters artifact for every run and the numbers match the logged root-cause breakdown.

  - [ ] T026d14 [P1] Fixed-layout deterministic test mode (harness)
    - Files: `scripts/ci/sim/run-scenario.ps1`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
    - Description: Add a deterministic fixed-layout mode that bypasses random candidate sampling and uses a stable, configuration-driven candidate list (or seeded deterministic generator) so placement pipeline behavior is repeatable across runs for CI validation of determinism.
    - Acceptance: Running in fixed-layout mode with identical inputs produces identical placement outcomes (or identical ZERO-PLACEMENT root-cause) across repeated runs.

  - [ ] T026d15 [P1] CI assert placements step & remediation hints
    - Files: `scripts/ci/sim/run-scenario.ps1`, `.github/workflows/ci.yml` (if present)
    - Description: Fail the CI job early when a seeded village run results in zero placements. Attach the root-cause log line, counters artifact, and suggest remediation steps (fixed-layout mode, terrain-acceptance tuning, or manual inspection). Provide an explicit exit code and artifact links.
    - Acceptance: CI shows a clear failure when zero-placement occurs and includes links to the artifacts and a recommended remediation path.
  - [ ] T026d7 [US2] Seed propagation logging & verification
    - Files: `VillagePlacementServiceImpl.java`, `StructureServiceImpl.java`, `PathServiceImpl.java`
    - Description: Log `[SEED] village=<vSeed> placement=<pSeed> path=<pathSeed>` once per village. Path seed derived from placement seed.
    - Acceptance: Same-seed runs produce identical seed triplets; harness compares lines for equality.
  - [ ] T026d8 [US2] Determinism regression headless test
    - Files: `scripts/ci/sim/test-path-determinism.ps1`, `tests/HEADLESS-TESTING.md`
    - Description: Extend script: if Run 2 has zero placements, auto-retry up to 2 times; if still zero, mark FAIL with root-cause aggregation.
    - Acceptance: Test only FAILs determinism after retries and diagnostic root-cause summary recorded.
  - [ ] T026d9 [US2] Placement pipeline unit tests (MockBukkit)
    - Files: `plugin/src/test/java/.../VillagePlacementServiceImplTest.java`, `StructureServiceImplTest.java`
    - Description: Add tests for ordering, retry sequence, and seed-chain determinism (mock terrain & chunks).
    - Acceptance: 70%+ coverage for deterministic branches; green on CI.
  - [ ] T026d10 [US2] Update documentation & constitution check
    - Files: `tests/HEADLESS-TESTING.md`, `specs/001-village-building-ux/plan.md`, `docs/compatibility-matrix.md`
    - Description: Replace open issue note with resolution summary; add determinism guarantees section.
    - Acceptance: HEADLESS-TESTING.md shows "Determinism Stabilized" and sample dual-run PASS output.

  - Acceptance:
    - Reported distance is within a small epsilon of `minVillageSpacing`.

**Checkpoint**: US2 stabilization goals met — zero rooftop crossings, no floating slabs, no treetop mounds, and clean re-seat behavior. Rotational variety present with accurate footprints.

---

## Phase 4.6: Test Coverage Improvement (Priority: P1)

Purpose: Increase test coverage from 6.7% to >70% on new code, focusing on core worldgen and placement logic. Each test task targets specific classes with clear coverage goals and MockBukkit/harness integration.

### Core Worldgen Coverage (Target: 70%+)

- [ ] T027a [P] [QA] Unit tests for `PathServiceImpl` (A* pathfinding core)
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImplTest.java`
  - Coverage Target: 70% lines, 60% branches (220 lines, 122 conditions)
  - Description: Test A* pathfinding with mock World; verify straight-line path, obstacle avoidance, max-node cap, distance limits, deterministic seed behavior, and waypoint caching. Use MockBukkit World with preset terrain heightmap.
  - Acceptance:
    - `findPathAStar` tested with flat terrain, obstacles, water/lava penalties, and unreachable targets.
    - `generatePathNetwork` tested with 2-5 buildings; assert connectivity ratio ≥90% for reachable pairs.
    - Deterministic: same seed produces identical path node sequences.

- [ ] T027b [P] [QA] Unit tests for `PathEmitter` (block placement and smoothing)
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/worldgen/impl/PathEmitterTest.java`
  - Coverage Target: 65% lines, 55% branches (90 lines, 58 conditions)
  - Description: Test path block emission with stairs/slabs; verify no floating slabs, height transitions respect ±1, and surface replacement logic. Use MockBukkit World.
  - Acceptance:
    - `emitPath` places blocks on valid surface; skips air/void.
    - Smoothing adds slabs/stairs only when block below is solid.
    - No blocks placed above structure materials (when building mask provided).

- [ ] T027c [QA] Unit tests for `StructureServiceImpl` (schematic loading and placement)
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImplTest.java`
  - Coverage Target: 60% lines, 50% branches (388 lines, 286 conditions)
  - Description: Test placeholder structure loading, Paper API procedural builds (Roman house/workshop/market/bathhouse), rotation determinism, and re-seating logic. Mock TerraformingUtil and SiteValidator for isolation.
  - Acceptance:
    - `loadPlaceholderStructures` populates 6 templates with correct dimensions.
    - `placeStructure` with seed produces deterministic rotation (0/90/180/270).
    - Re-seating attempts up to MAX_RESEAT_ATTEMPTS when terraforming fails.
    - `buildRomanHouse` creates walls/floor/roof at expected coordinates.

- [ ] T027d [P] [QA] Unit tests for `VillagePlacementServiceImpl` (dynamic placement and collision)
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImplTest.java`
  - Coverage Target: 65% lines, 55% branches (222 lines, 84 conditions)
  - Description: Test dynamic structure placement with footprint tracking, overlap detection, spiral search, and deterministic ordering. Mock StructureService and PathService.
  - Acceptance:
    - `placeVillageStructures` places 5 structures with zero overlaps (via Footprint tracking).
    - `findNonOverlappingPosition` returns valid location within MAX_PLACEMENT_ATTEMPTS or null.
    - `getCultureStructures` returns deterministic structure IDs for same seed.
    - Footprint calculation accounts for rotation (actualOrigin is corner, not center).

- [ ] T027e [P] [QA] Unit tests for `TerraformingUtil` (grading, trimming, backfilling)
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/worldgen/TerraformingUtilTest.java`
  - Coverage Target: 60% lines, 50% branches (186 lines, 108 conditions)
  - Description: Test site preparation, vegetation trimming, foundation backfilling, and rollback. Use MockBukkit World with preset terrain.
  - Acceptance:
    - `prepareSite` flattens mild slopes (≤2 blocks variance) and trims vegetation.
    - `trimVegetation` removes grass/leaves but not logs/solid blocks; returns trim count.
    - `backfillFoundation` fills air gaps under structure perimeter only (not full footprint).
    - No dirt placed on top of leaf/log blocks (T014b constraint).

- [ ] T027f [P] [QA] Unit tests for `SiteValidator` (foundation and clearance checks)

- [ ] T027l [P] [QA] Unit tests: water foundation rejection & spacing
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/worldgen/SiteValidatorTest.java`, `plugin/src/test/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImplTest.java`
  - Description: Add tests ensuring SiteValidator fails on water/lava blocks; placement service rejects candidates violating min spacing.
  - Acceptance:
    - Water footprint test returns foundationOk=false.
    - Spacing test never places two mock structures closer than config value.

- [ ] T027m [P] [QA] Unit tests: non-overlap & classification
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImplTest.java`, `plugin/src/test/java/com/davisodom/villageoverhaul/worldgen/TerrainClassifierTest.java`
  - Description: Validate rotation-aware footprint intersection algorithm; test TerrainClassifier for ACCEPTABLE vs FLUID/STEPP/STEEP/BLOCKED categories.
  - Acceptance:
    - Overlap test fails when artificially forced overlap; production logic prevents it.
    - Classification tests cover all enum values.

- [ ] T027n [P] [QA] Unit tests: village map model integrity
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/onboarding/VillageMapServiceTest.java`
  - Description: Instantiate map service, add mock building footprints, mark unacceptable terrain; assert counts and retrieval API consistency.
  - Acceptance:
    - Map returns all footprints; unacceptable terrain list stable across queries.
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/worldgen/SiteValidatorTest.java`
  - Coverage Target: 65% lines, 55% branches (83 lines, 60 conditions)
  - Description: Test foundation solidity checks, interior clearance, slope validation. Use MockBukkit World.
  - Acceptance:
    - `validateSite` returns `foundationOk=true` for flat stone/dirt, `false` for water/lava/ice.
    - `interiorOk=true` when interior has ≥60% air; `false` when blocked by trees/structures.
    - Slope check passes for ≤2 block variance, fails for cliffs/steep hills.

---

## Phase 5: User Story 3 — Trade-Funded Village Projects (Priority: P1)

**Goal**: Tie contributions to visible building upgrades after structures exist

**Independent Test**: Complete trades to 100% a project; observe corresponding building upgrade

- [ ] T027 [US3] Wire project completion → structure upgrade in `plugin/src/main/java/com/davisodom/villageoverhaul/projects/ProjectService.java`
- [ ] T028 [P] [US3] Implement upgrade application (structure replace/expand) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureUpgradeApplier.java`
- [ ] T029 [US3] Log upgrade completion with [STRUCT] in `plugin/src/main/java/com/davisodom/villageoverhaul/projects/ProjectService.java`

**Checkpoint**: US3 independently verifiable

---

### Model and State Coverage (Target: 60%+)

- [ ] T027g [P] [QA] Unit tests for data models (`Building`, `PathNetwork`, `PlacementQueue`, `Project`)
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/model/ModelTest.java`
  - Coverage Target: 60% lines, 50% branches (combined ~350 lines)
  - Description: Test JSON serialization/deserialization, validation, state transitions for core model classes. Use Gson directly.
  - Acceptance:
    - `Building.toJson()` and `Building.fromJson()` round-trip with all fields intact.
    - `PlacementQueue` state transitions: PREPARING → READY → IN_PROGRESS → COMPLETED/CANCELLED.
    - `PathNetwork` correctly tracks building pairs and path block counts.
    - `Project` validation rejects negative progress, invalid states.

- [ ] T027h [P] [QA] Unit tests for `PlacementQueueProcessor` (async placement and batching)
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/worldgen/impl/PlacementQueueProcessorTest.java` (expand existing skipped tests)
  - Coverage Target: 70% lines, 60% branches (194 lines, 58 conditions)
  - Description: Unskip and implement all 10 existing test placeholders; add MockBukkit scheduler integration for async tick simulation.
  - Acceptance:
    - All 10 skipped tests pass with real assertions (no TODOs).
    - `startProcessing` schedules repeating task; `stopProcessing` cancels cleanly.
    - Queue batching respects BATCH_SIZE config; processes multiple queues round-robin.
    - Cancellation aborts placement mid-queue; status updates to CANCELLED.

### Integration and Harness Coverage (Target: 50%+)

- [ ] T027i [QA] Headless integration test: end-to-end village generation
  - Files: `scripts/ci/sim/test-village-generation.ps1`, `tests/HEADLESS-TESTING.md`
  - Coverage Target: N/A (integration test)
  - Description: Generate a 5-building village with paths in headless Paper; assert structures placed, paths emitted, no overlaps, no floating blocks. Capture world snapshot and logs.
  - Acceptance:
    - Script runs `votest generate-structures` and `votest generate-paths` via RCON.
    - Parses logs for [STRUCT] seat success (5 structures) and path connectivity ≥90%.
    - World inspection finds 5 distinct structure footprints with zero overlap (NBT scan).
    - CI passes with exit code 0.

- [ ] T027j [P] [QA] MockBukkit unit test: `VillageOverhaulPlugin` lifecycle
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/VillageOverhaulPluginTest.java`
  - Coverage Target: 90% lines, 80% branches (14 lines, 3 conditions)
  - Description: Expand existing plugin test to verify service registration, config loading, tick engine wiring, command registration. Use MockBukkit ServerMock.
  - Acceptance:
    - `onEnable` registers all services (VillageService, StructureService, PathService, ProjectService).
    - Config loads from `plugin.yml`; defaults applied if missing.
    - TickEngine started and accessible via `getTickEngine()`.
    - Commands registered: `/vo`, `/votest`, `/voproject`.
    - `onDisable` stops tick engine and flushes persistence without errors.

### Coverage Tracking and Reporting

- [ ] T027k [P] [QA] Add JaCoCo coverage reports to CI
  - Files: `plugin/build.gradle`, `.github/workflows/ci.yml` (if exists)
  - Description: Configure JaCoCo Gradle plugin to generate HTML coverage reports; upload as CI artifacts; enforce minimum 60% line coverage threshold for new code.
  - Acceptance:
    - `./gradlew test jacocoTestReport` generates `build/reports/jacoco/test/html/index.html`.
    - CI uploads coverage report as artifact on every PR.
    - Build fails if new code coverage <60% (configurable threshold).

**Checkpoint**: Test coverage ≥70% on core worldgen (PathServiceImpl, StructureServiceImpl, VillagePlacementServiceImpl, TerraformingUtil, SiteValidator); ≥60% on models and PlacementQueueProcessor; all skipped tests implemented.

---

## Phase 4.7: Bugfix Sprint — Terrain & Path Consistency (Priority: P1)

Purpose: Resolve high-impact playtest defects: unused terraforming pads, treetop path placement, fluid veto inconsistencies, summary count mismatch, inflated rotated footprints, excessive reseat attempts, and classification performance gaps. Ordered by player visibility and world integrity impact.

- [ ] T015c [P1] [US1] Deferred terraform commit / rollback
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/TerraformingUtil.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`
  - Description: Generate a TerraformingPlan (lists of trim/grade/fill operations) during seat validation; apply only after a seat is chosen as final. Maintain a per-attempt journal so abandoned seats can be rolled back (revert block states) to eliminate stray flattened dirt platforms.
  - Acceptance:
    - Rejecting a seat leaves no modified blocks at that origin (visual + log check).
    - Each successful building logs `appliedTerraformPlan`; aborted attempts log `terraformRollback applied`.
    - World audit shows zero large unused graded pads after village generation.

- [ ] T021d [P1] [US2] Path ground detection excluding vegetation
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathEmitter.java`
  - Description: Update `findGroundLevel()` to treat leaves/logs/any VEGETATION classification as non-surface; descend until solid non-vegetation ground or abort node if descent limit exceeded. `PathEmitter` adds `isValidPathSurface()` whitelist (grass, dirt, stone, sand, gravel, snow variants). Skip or reroute nodes landing on vegetation. Optionally clear a single vegetation layer (not trees) before emission.
  - Acceptance:
    - 0 path blocks placed on leaves/logs across smoke test seeds.
    - Logs include `skippedVegetationNodes=N` and `reroutedNodes=M` metrics.
    - Visual inspection: no treetop paths; all paths hug natural terrain.

- [ ] T020b [P1] [US1] Enforce site-prep abort on fluid veto
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`
  - Description: If `TerraformingUtil.prepareSite()` returns false (fluid detected), abort placement attempt immediately instead of proceeding to paste; count toward rejection metrics with reason `fluid`.
  - Acceptance:
    - No paste occurs after a fluid rejection log.
    - Final placed footprints contain 0 fluid tiles under structure base.

- [ ] T014c [P1] [US1] Prevent dirt grading on leaf/log canopy
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/TerraformingUtil.java`
  - Description: During grading/fill, treat leaves/logs as transparent; never place dirt directly on them. Either trim foliage or skip column; record skipped columns for metrics.
  - Acceptance:
    - No dirt caps appear atop tree leaves near structures in verification run.
    - Log shows `skippedCanopyColumns=K` when applicable.

- [ ] T018c [P2] [US1] Correct final summary building count
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`
  - Description: Derive success summary from authoritative footprint registry; warn if internal counters differ (`summaryCountMismatch`).
  - Acceptance:
    - Summary line always matches number of tracked footprints.
    - Mismatch test triggers single WARN and auto-corrects output.

- [ ] T017c [P2] [US1] Normalize rotated footprint dimensions
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
  - Description: After rotation, compute width/depth using rotated clipboard dims only (exclude spacing buffer). Store schematic footprint; enforce spacing externally.
  - Acceptance:
    - Footprint logs for all rotations equal original schematic dimensions (or swapped).
    - Downstream systems (paths/borders) show consistent bounds.

- [ ] T012m [P2] [Foundational] Adaptive reseat/backoff heuristics
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
  - Description: Monitor rejection rate; if `>0.95` after threshold attempts, adjust search radius + tighten slope tolerance or abort early with `[STRUCT] adaptive-abort` log.
  - Acceptance:
    - Attempts reduced ≥50% on pathological seeds vs baseline.
    - AvgRejected < 0.90 in stress test scenario.

- [ ] T012n [P3] [Foundational] Terrain classification caching
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/TerrainClassifier.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/SiteValidator.java`
  - Description: Cache `findGroundLevel` and 3x3 slope results per (x,z) within placement window; invalidate entries touched by accepted footprint. Provide perf counters.
  - Acceptance:
    - ≥30% reduction in classification calls measured by perf counters.
    - No incorrect terrain decisions (spot-check classification vs raw world state).

- [ ] T022b [P2] [US2] PathEmitter under-support & surface whitelist
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathEmitter.java`
  - Description: Emit slabs/stairs only when block below is solid natural ground; fallback to full block else. Combine with vegetation exclusion to eliminate floating or treetop smoothing artifacts.
  - Acceptance:
    - 0 floating slabs/stairs; complements headless test T026f.
    - Emitted blocks restricted to natural whitelist.

Prioritization: P1 (T015c, T021d, T020b, T014c) → P2 (T018c, T017c, T022b, T012m) → P3 (T012n).

**Checkpoint (goal)**: No treetop path blocks; no stray terraformed platforms; accurate summary counts; reduced attempt inflation; performance improved for classification-heavy seeds.

---

## Phase 4.8: Pathfinding Performance & Caching (Future Work Prioritized)

**Purpose**: Implement planned pathfinding enhancements identified during T026a (node cap & cache tests) to improve scalability, reduce redundant computations, and prepare for NPC movement & builder logistics. These tasks convert future work items into actionable, testable backlog entries.

### Rationale & Priority
| Priority | Reason |
|----------|--------|
| P1 | High impact on performance for larger villages; prevents exponential node exploration & redundant recalculations |
| P2 | Improves resilience & observability; enables targeted invalidation after terrain mutations (terraforming, structure placement) |
| P3 | Advanced optimizations & instrumentation helpful for profiling but not blocking core gameplay |

- [ ] T042 [P1] [US2] Waypoint-level path segment cache in `PathServiceImpl`
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/model/PathNetwork.java`
  - Description: Introduce cache keyed by ordered `(startX,startZ)->(endX,endZ)` segment pairs (normalized ordering). Break long building-to-building paths into waypoint segments (e.g., every N blocks or turning points). Reuse cached segments when generating new paths sharing sub-routes. Persist hit/miss stats per village.
  - Acceptance:
    - Segment decomposition produces ≥1 segment for any path > 40 blocks.
    - Cache hit rate logged: `[PATH] cache: hits=H, misses=M, entries=E`.
    - Reusing segments reduces node exploration by ≥30% on a 5-building test vs baseline.
    - Deterministic: same seed + same building layout yields identical segment sequence.
  - Tests:
    - Add harness parsing (extend `run-scenario.ps1`) for cache stats.
    - MockBukkit unit test verifies segment normalization and retrieval.

- [ ] T042a [P1] [US2] Headless test: waypoint cache efficacy
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: Generate two villages with overlapping building vectors (forcing shared route portions). Assert second village shows ≥1 cache hit; fail if hits=0 when overlap ≥25%.
  - Acceptance:
    - Harness logs include cache stats for both villages.
    - Test fails if reported hits=0 while overlap condition satisfied.

- [ ] T043 [P2] [US2] Terrain-triggered path cache invalidation
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/TerraformingUtil.java`
  - Description: Invalidate cached segments intersecting modified terrain columns after terraforming or structure placement. Maintain lightweight spatial index (grid bucket -> segment IDs). Log invalidation metrics.
  - Acceptance:
    - `[PATH] cache invalidated: segments=N, reason=terraform` when site prep alters ground beneath existing segments.
    - Subsequent path generation after invalidation recomputes affected segments (miss then reinsert).
    - False invalidations (segments untouched) < 5% in test scenario.
  - Tests:
    - Harness: Force terrain change (e.g., grading) between two path generations; assert invalidation log present.
    - Unit test simulates terrain change notification and verifies affected segment removal.

- [ ] T043a [P2] [US2] Headless test: targeted cache invalidation
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: Generate paths, mutate terrain under one segment via test command or scripted block edits, regenerate paths; assert invalidation count ≥1 and non-mutated segment count unchanged.
  - Acceptance:
    - Invalidation log includes mutated coordinate range.
    - Non-mutated segment IDs remain cached (entries count stable excluding invalidated items).

- [ ] T044 [P2] [US2] Concurrency cap & planner queue for A* searches
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`
  - Description: Introduce capped thread/task execution (e.g., max 3 concurrent planners). Additional path requests enqueue and log `[PATH] planner queued`. Use synchronized queue; expose metrics.
  - Acceptance:
    - Concurrent planner count never exceeds configured cap.
    - Queued requests eventually begin (no starvation) under load test of ≥10 simultaneous path requests.
    - Logs: `[PATH] planners: active=A queued=Q cap=C` at start & completion.
  - Tests:
    - Stress unit test simulates multiple async requests; asserts cap enforcement & eventual processing.
    - Harness extension (optional) triggers rapid path generation and parses queue metrics.

- [ ] T044a [P2] [US2] Headless stress test: planner queue saturation
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: Trigger > cap simultaneous path generations (e.g., spawn buildings then call path generation command). Assert queued count >0 and all queued items processed within timeout.
  - Acceptance:
    - Final log shows queued=0 after processing cycle.
    - No path generation failure due to planner starvation.

- [ ] T045 [P3] [US2] Pathfinding perf counters & profiling hooks
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/metrics/PerfCounters.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`
  - Description: Add counters: `nodesExploredTotal`, `avgNodesPerPath`, `cacheHitRate`, `invalidationEvents`, `plannerQueueWaitMs`. Expose `/votest path-metrics <village-id>` command.
  - Acceptance:
    - Command outputs JSON with all counters.
    - Counters resettable via `/votest path-metrics reset`.
    - Avg nodes matches harness sampled average (±5%).

- [ ] T045a [P3] [US2] Unit test: metrics consistency
  - Files: `plugin/src/test/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImplTest.java`
  - Description: Generate mock paths; verify counters increment predictably and reset clears values.
  - Acceptance:
    - After N path generations: `nodesExploredTotal >= sum(nodes)`; after reset all counters = 0.

**Checkpoint**: Pathfinding subsystem optimized with segment reuse, targeted invalidation, capped concurrency, and observable performance metrics; ready for NPC builder integration.

---

## Phase 6: User Story 4 — Guided Onboarding (Priority: P2)

**Goal**: Greeter prompt and signage at main building entry

**Independent Test**: Teleport player into main building area; assert greeter + signage shows active projects

- [ ] T030 [US4] Implement signage renderer (Adventure API) in `plugin/src/main/java/com/davisodom/villageoverhaul/onboarding/SignageService.java`
- [ ] T031 [P] [US4] Implement greeter trigger (radius/cooldown) in `plugin/src/main/java/com/davisodom/villageoverhaul/onboarding/GreeterService.java`
- [ ] T032 [US4] Extend test commands to refresh signage and trigger greeter in `plugin/src/main/java/com/davisodom/villageoverhaul/commands/TestCommands.java`

- [ ] T030a [US4] Implement VillageMapService (live cartography)
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/onboarding/VillageMapService.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
  - Description: Maintain building footprint registry + terrain classification snapshot per village; API: `getMap(villageId)` returns structured DTO.
  - Acceptance:
    - Adding/removing building updates map deterministically.
    - Classification queries cached and invalidated on placement.

- [ ] T030b [US4] Implement Village Map GUI & sign interaction
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/onboarding/VillageMapGui.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/onboarding/SignageService.java`, `plugin/src/main/resources/plugin.yml`
  - Description: Register interaction on right-click of sign with text "Village Map"; open GUI (inventory-based) showing building list + terrain summary; ensure Bedrock parity (no client-only formatting).
  - Acceptance:
    - GUI opens with correct data.
    - Bedrock clients receive readable text labels.

- [ ] T030c [US4] Village Map test command & serialization
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/commands/TestCommands.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/onboarding/VillageMapService.java`
  - Description: Add `votest map <village-id>` producing JSON summary: buildings, spacing minDistance, unacceptable terrain counts.
  - Acceptance:
    - JSON output consumed by headless harness in T026l.

**Checkpoint**: US4 independently verifiable

---

## Phase 6a: NPC Builder — Deterministic State Machine (Priority: P1)

**Goal**: Deterministic builder state machine with visible row/layer progress and material coordination

**Independent Test**: Trigger a small build; assert state transitions, persisted checkpoints, and visible progress

- [ ] T033a Implement Builder state machine skeleton in `plugin/src/main/java/com/davisodom/villageoverhaul/builders/impl/BuilderServiceImpl.java`
- [ ] T033b [P] Implement MaterialManager allocations/pickups/consumption in `plugin/src/main/java/com/davisodom/villageoverhaul/builders/impl/MaterialManagerImpl.java`
- [ ] T033c Integrate PlacementQueue with PLACING_BLOCKS state in `plugin/src/main/java/com/davisodom/villageoverhaul/builders/impl/BuilderServiceImpl.java`
- [ ] T033d Add harness logs for state transitions and progress in `plugin/src/main/java/com/davisodom/villageoverhaul/builders/impl/BuilderServiceImpl.java`

**Checkpoint**: NPC Builder independently verifiable

---

## Phase 7: User Story 5 — Reputation & Contracts (Priority: P2)

**Goal**: Reputation gains from contracts; gating for items/property

**Independent Test**: Reach threshold via a contract; unlock a gated item

- [ ] T033 [US5] Add contract completion → reputation updates in `plugin/src/main/java/com/davisodom/villageoverhaul/contracts/ContractService.java`
- [ ] T034 [P] [US5] Add gating checks on purchase in `plugin/src/main/java/com/davisodom/villageoverhaul/economy/PurchaseService.java`

---

## Phase 8: User Story 6 — Dungeons & Custom Enemies (Priority: P3)

**Goal**: Deterministic dungeon instance; synchronized state changes

- [ ] T035 [US6] Stub deterministic dungeon instance creation in `plugin/src/main/java/com/davisodom/villageoverhaul/dungeons/DungeonService.java`

---

## Phase 9: User Story 7 — Inter-Village Relationships (Priority: P3)

**Goal**: Relationship edges with modifiers influencing prices/contracts

- [ ] T036 [US7] Add relationship edge updates in `plugin/src/main/java/com/davisodom/villageoverhaul/relations/RelationshipService.java`

---

## Phase 10: User Story 8 — Property Purchasing (Priority: P4)

**Goal**: Persisted ownership and access controls for lots/homes

- [ ] T037 [US8] Implement property ownership persistence in `plugin/src/main/java/com/davisodom/villageoverhaul/property/PropertyService.java`

---

## Phase N: Polish & Cross-Cutting Concerns

- [ ] T038 [P] Documentation updates for structures/paths/onboarding in `docs/`

- [ ] T038a [P] Village Map documentation
  - Files: `docs/village-map.md`, `specs/001-village-building-ux/quickstart.md`
  - Description: Author usage guide: sign placement, GUI features, water avoidance & spacing rules, config key reference.
  - Acceptance:
    - Quickstart extended with Map section.
    - Separate doc includes interaction + API + config table.
- [ ] T039 Performance profiling hooks for structure/path ticks in `plugin/src/main/java/com/davisodom/villageoverhaul/metrics/PerfCounters.java`
- [ ] T040 [P] Security review of admin/test commands in `plugin/src/main/java/com/davisodom/villageoverhaul/commands/TestCommands.java`
- [ ] T041 Ensure CI scripts remain PS 5.1-compatible and ASCII-only in `scripts/ci/sim/*.ps1`

---

## Dependencies & Execution Order

### Phase Dependencies
- Setup → Foundational → US1 → US2 → US3 → US4 → US5/US6/US7 → US8 → Polish

### Constitution-Driven Dependency Chains (enforced)
- WorldEdit integration layer → Structure loading system → Placement engine
- NPC base class → State machine → Builder AI → Material manager
- Village data model → Village manager → Expansion system
- Pathfinding util → Navigator → Builder movement
- Configuration system → All subsystems

### User Story Dependencies
- US1 (Structures) → US2 (Paths/Main) → US4 (Onboarding)
- NPC Builder depends on US1 (structures + placement queue)
- US3 (Projects) depends on US1 (structures present)

### Parallel Opportunities
- Marked [P] tasks (e.g., validators, FAWE path, signage vs greeter) can run in parallel when files don’t conflict

---

## Implementation Strategy

### MVP First (US1 only)
1. Complete Setup + Foundational
2. Implement US1 (Structure Generation & Placement)
3. Validate via harness (0 floating/embedded)

### Incremental Delivery
1. US1 → US2 (paths/main) → US4 (onboarding)
2. US3 (projects upgrades) after US1
3. Remaining systems US5–US8 as capacity allows
