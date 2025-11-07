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

- [ ] T012h [Foundational] Persist and update dynamic village borders
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/VillageMetadataStore.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
  - Description: Represent village border as axis-aligned bounds; expand deterministically on building placement; persist and expose getters.
  - Acceptance:
    - After building placements, border bounds expand to include all footprints.
    - Border persisted and retrievable across restarts.

- [ ] T012i [Foundational] Enforce inter-village spacing during village site search
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/commands/GenerateCommand.java`
  - Description: During site selection (user-invoked and natural), reject candidate sites whose border would violate `minVillageSpacing` vs any existing village border (border-to-border, bidirectional).
  - Acceptance:
    - Attempted placements within `minVillageSpacing` are rejected with `[STRUCT]` logs indicating `rejectedVillageSites.minDistance`.
    - Successful placements always satisfy min border distance.

- [ ] T012j [Foundational] Spawn-proximal initial placement & nearest-neighbor bias
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
  - Description: Bias first/early villages to spawn proximity (not exact spawn). For subsequent villages, prefer the nearest valid site to existing borders while respecting the minimum distance.
  - Acceptance:
    - First village placed within a configured radius of spawn (not exactly at spawn).
    - Subsequent villages placed at the smallest valid distance ≥ `minVillageSpacing`.

- [ ] T012k [Foundational] Observability for inter-village enforcement
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
  - Description: Emit structured logs/metrics for inter-village rejections and border-limited expansions.
  - Acceptance:
    - Logs include counters like `rejectedVillageSites.minDistance=NN` and border expansion clips.

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

- [ ] T020a [US1] Enforce water avoidance & spacing in structure seating
  - Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/SiteValidator.java`
  - Description: Extend seating logic to abort if any foundation/interior block is FLUID. Include spacing pre-check before detailed validation. Update logs with `[STRUCT] seat rejected: fluid` or `spacing`.
  - Acceptance:
    - No building placed with any water/lava under footprint.
    - Logs show rejection reason when failing due to fluid or spacing.
    - Existing successful seats unaffected (still grounded & interior air ok).

**Checkpoint**: US1 independently verifiable via headless harness

---

## Phase 4: User Story 2 — Path Network & Main Building (Priority: P1)

**Goal**: Connect key buildings with traversable paths and designate exactly one main building per village

**Independent Test**: Verify path connectivity ratio and a single persisted main building

- [X] T021 [US2] Implement `PathServiceImpl` (A* heightmap + smoothing) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`
- [X] T022 [P] [US2] Emit path blocks with minimal smoothing (steps/slabs) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathEmitter.java`
- [ ] T023 [US2] Implement main building designation logic in `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/MainBuildingSelector.java`
- [ ] T024 [US2] Persist mainBuildingId and pathNetwork in `plugin/src/main/java/com/davisodom/villageoverhaul/villages/VillageMetadataStore.java`
- [ ] T025 [US2] Extend test command: `votest generate-paths <village-id>` in `plugin/src/main/java/com/davisodom/villageoverhaul/commands/TestCommands.java`
- [ ] T026 [US2] Harness assertion for path connectivity ≥ 90% in `scripts/ci/sim/run-scenario.ps1`
- [ ] T026a [US2] Add tests for pathfinding concurrency cap and waypoint cache invalidation in `scripts/ci/sim/run-scenario.ps1`
- [ ] T026b [P] [US2] Add headless test for path generation between distant buildings (within 200 blocks) in `scripts/ci/sim/run-scenario.ps1`; assert non-empty path blocks between two buildings ≥120 blocks apart (and within MAX_SEARCH_DISTANCE), or graceful skip if out-of-range. Update `tests/HEADLESS-TESTING.md` with run notes.
- [ ] T026c [P] [US2] Add terrain-cost accuracy integration test in `scripts/ci/sim/run-scenario.ps1`: construct two candidate routes (flat vs water/steep) and assert chosen path avoids higher-cost tiles when a comparable-length flat route exists. Document setup in `tests/HEADLESS-TESTING.md`.
- [ ] T026d [P] [US2] Add deterministic path-from-seed check in `scripts/ci/sim/run-scenario.ps1`: run path generation twice with the same seed and hash the ordered (x,y,z) path blocks; assert identical hashes; with a different seed, assert hash changes. Capture artifacts under `test-server/logs/`.

**Checkpoint**: US2 independently verifiable

---

## Phase 5: User Story 3 — Trade-Funded Village Projects (Priority: P1)

**Goal**: Tie contributions to visible building upgrades after structures exist

**Independent Test**: Complete trades to 100% a project; observe corresponding building upgrade

- [ ] T027 [US3] Wire project completion → structure upgrade in `plugin/src/main/java/com/davisodom/villageoverhaul/projects/ProjectService.java`
- [ ] T028 [P] [US3] Implement upgrade application (structure replace/expand) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureUpgradeApplier.java`
- [ ] T029 [US3] Log upgrade completion with [STRUCT] in `plugin/src/main/java/com/davisodom/villageoverhaul/projects/ProjectService.java`

**Checkpoint**: US3 independently verifiable

---

## Phase 4.5: US2 Stabilization & Terrain Integration (Priority: P1)

Purpose: Fix rooftop paths, floating path slabs, treetop dirt, unused terraforming, and rotation variety while keeping the current stable build intact. These tasks target minimal, safe changes with clear acceptance criteria and tests.

- [ ] T021b [US2] Register and avoid building footprints in pathfinding
	- Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/PathService.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`
	- Description: Add a method to register building bounds (minX..maxX, minY..maxY, minZ..maxZ) with the path service per village, and treat any node inside these bounds as an obstacle during A*; populate from actual placed buildings right before path generation.
	- Acceptance:
		- 0 blocks of any path are placed on top of structure materials (roof/walls/floors) when buildings are adjacent.
		- Path generation logs show "avoided N building tiles" when applicable.

- [ ] T021c [US2] Reinstate 3D terrain-following (±1 Y per step) with natural terrain whitelist
	- Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`
	- Description: Restore 3D A* (consider Y) with a whitelist of natural ground (grass/dirt/stone/sand/gravel/packed_ice/snow) so paths will not route over man-made blocks (wood/planks/bricks/terracotta/concrete/wool). Keep MAX_NODES and distance caps as before.
	- Acceptance:
		- Paths follow gentle slopes; no flat floating spans across ledges.
		- Paths refuse to climb onto non-natural blocks; rooftop crossings eliminated.

- [ ] T022a [P] [US2] Fix floating slabs/stairs in PathEmitter
	- Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathEmitter.java`
	- Description: When smoothing, only place slab/stair if (a) target block is path material and (b) the block below is solid natural terrain; otherwise downgrade to full block at ground or skip smoothing step. Remove any previously placed slab that would end up floating.
	- Acceptance:
		- No slabs/stairs render with air directly beneath; zero "floating slab" sightings in smoke test.

- [ ] T014b [P] [US1] Prevent dirt mounds on treetops during grading
	- Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/TerraformingUtil.java`
	- Description: Before fill/grade, treat leaves/logs as non-foundation; never place dirt on top of leaf/log blocks. Prefer trimming foliage and seeking true ground (soil/stone) or skipping fill for that column.
	- Acceptance:
		- After structure placement or path emission, no dirt columns cap tree leaves; canopy remains natural or trimmed, not buried.

- [ ] T015b [US1] Roll back unused terraforming when a seating attempt is abandoned
	- Files: `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/TerraformingUtil.java`
	- Description: When seating attempts fail and the algorithm re-seats elsewhere, revert prior grading/trim/fill in that attempt (simple block-change journal scoped to the attempt). Commit changes only on final successful seat.
	- Acceptance:
		- No stray graded patches or dirt fills remain at rejected sites; logs show "terraform rollback applied" for aborted seats.

- [ ] T017b [P] [US1] Diversify building rotation while preserving footprint accuracy
	- Files: `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/VillagePlacementServiceImpl.java`, `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`
	- Description: Use per-building mixed seed (e.g., hash of villageId, structureId, index) for 0/90/180/270; ensure the same rotation is used for both footprint computation and paste. Do not allow vertical flips.
	- Acceptance:
		- In a 5-building village, at least two distinct rotations occur consistently; no overlaps; structures remain grounded.

- [ ] T026e [P] [US2] Headless test: "no rooftop paths"
	- Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
	- Description: Detect any path block whose underlying or target block is non-natural and belongs to a building footprint; fail if found. Capture a small world snapshot or log hash.

- [ ] T026f [P] [US2] Headless test: "no floating smoothing blocks"
	- Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
	- Description: Scan emitted path segments; assert slabs/stairs have solid under-support or are replaced by full blocks.

- [ ] T026g [P] [US1] Headless test: "no treetop dirt mounds"
	- Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
	- Description: In the affected area, ensure no dirt/grass blocks sit directly atop leaf/log blocks introduced by grading.

- [ ] T026h [P] [US1] Headless test: "terraform rollback on abort"

- [ ] T026i [P] [US1] Headless test: "reject water foundations"
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: Force generation near shallow water and assert all candidate seats over water are rejected; log count; succeed only if final placements exclude fluid tiles.
  - Acceptance:
    - Harness fails if any placed building footprint includes water/lava.
    - Log contains `rejectedFluidSeats=NN` metric.

- [ ] T026j [P] [US1] Headless test: "spacing enforcement"
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: Generate a dense village scenario; compute min inter-footprint distance; assert ≥ configured spacing.
  - Acceptance:
    - Test prints minDistance and configuredSpacing; fails if minDistance < configuredSpacing.

- [ ] T026k [P] [US1] Headless test: "non-overlapping footprints"
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: Hash all footprint block coords and assert no duplicates; verify count matches building count.
  - Acceptance:
    - Fails if any overlap detected.

- [ ] T026l [P] [US4] Headless test: "village map integrity"
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: After generation, query map service via test command, parse returned footprint and terrain summary; assert counts match placed structures, and unacceptable tiles summary non-zero when near fluids.
  - Acceptance:
    - Map reports each placed building exactly once.
    - Terrain classification totals reflect environment.
	- Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
	- Description: Simulate a forced re-seat; assert no net block changes remain at the first attempt location after rollback.

- [ ] T026m [P] [US1] Headless test: "inter-village spacing enforcement"
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: Attempt to place a second village within `minVillageSpacing` of the first; assert rejection; then place just beyond and assert success.
  - Acceptance:
    - Harness fails if any village borders are closer than configured spacing.
    - Log contains `rejectedVillageSites.minDistance=NN` metric.

- [ ] T026n [P] [US1] Headless test: "spawn-proximal initial village"
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: With a fresh world, assert the first village is generated within the configured spawn proximity range (not at exact spawn).
  - Acceptance:
    - Test prints distance to spawn and threshold; fails if outside range or equals 0.

- [ ] T026o [P] [US1] Headless test: "nearest-neighbor bias"
  - Files: `scripts/ci/sim/run-scenario.ps1`, `tests/HEADLESS-TESTING.md`
  - Description: With an existing village, request a new village and assert its distance to the nearest neighbor’s border is minimized subject to the spacing constraint.
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

