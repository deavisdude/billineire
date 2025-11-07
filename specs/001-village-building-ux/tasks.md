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

**Checkpoint**: Services and DTOs exist; admin commands compile; ready to implement US1

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
- [X] T019 [US1] Add [STRUCT] logs: begin/seat/re-seat/abort with seed inputs in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/StructureServiceImpl.java`
- [X] T020 [US1] Update harness parsing to assert "0 floating/embedded" in `scripts/ci/sim/run-scenario.ps1`

**Checkpoint**: US1 independently verifiable via headless harness

---

## Phase 4: User Story 2 — Path Network & Main Building (Priority: P1)

**Goal**: Connect key buildings with traversable paths and designate exactly one main building per village

**Independent Test**: Verify path connectivity ratio and a single persisted main building

- [ ] T021 [US2] Implement `PathServiceImpl` (A* heightmap + smoothing) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathServiceImpl.java`
- [ ] T022 [P] [US2] Emit path blocks with minimal smoothing (steps/slabs) in `plugin/src/main/java/com/davisodom/villageoverhaul/worldgen/impl/PathEmitter.java`
- [ ] T023 [US2] Implement main building designation logic in `plugin/src/main/java/com/davisodom/villageoverhaul/villages/impl/MainBuildingSelector.java`
- [ ] T024 [US2] Persist mainBuildingId and pathNetwork in `plugin/src/main/java/com/davisodom/villageoverhaul/villages/VillageMetadataStore.java`
- [ ] T025 [US2] Extend test command: `votest generate-paths <village-id>` in `plugin/src/main/java/com/davisodom/villageoverhaul/commands/TestCommands.java`
- [ ] T026 [US2] Harness assertion for path connectivity ≥ 90% in `scripts/ci/sim/run-scenario.ps1`
- [ ] T026a [US2] Add tests for pathfinding concurrency cap and waypoint cache invalidation in `scripts/ci/sim/run-scenario.ps1`

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

## Phase 6: User Story 4 — Guided Onboarding (Priority: P2)

**Goal**: Greeter prompt and signage at main building entry

**Independent Test**: Teleport player into main building area; assert greeter + signage shows active projects

- [ ] T030 [US4] Implement signage renderer (Adventure API) in `plugin/src/main/java/com/davisodom/villageoverhaul/onboarding/SignageService.java`
- [ ] T031 [P] [US4] Implement greeter trigger (radius/cooldown) in `plugin/src/main/java/com/davisodom/villageoverhaul/onboarding/GreeterService.java`
- [ ] T032 [US4] Extend test commands to refresh signage and trigger greeter in `plugin/src/main/java/com/davisodom/villageoverhaul/commands/TestCommands.java`

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

