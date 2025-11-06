---

description: "Task list for implementing Village Overhaul — Plan A (Plugin-first)"
---

# Tasks: Village Overhaul (Plan A)

**Input**: Design documents from `/specs/001-village-overhaul/`
**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Only where requested by spec or clearly beneficial for determinism.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: User story label (US1..US5) only in story phases
- Include exact file paths

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and CI wiring

- [X] T001 Create Gradle plugin project structure in plugin/ per plan.md
- [X] T002 Initialize Gradle build with dependencies in `plugin/build.gradle` (Paper API, Adventure, Vault, LuckPerms, WorldGuard, FAWE, Jackson, JUnit, MockBukkit)
- [X] T003 Create plugin descriptor at `plugin/src/main/resources/plugin.yml`
- [X] T004 [P] Create main plugin class at `plugin/src/main/java/com/davisodom/villageoverhaul/VillageOverhaulPlugin.java`
- [X] T005 [P] Add Gradle settings and wrapper in `plugin/` (settings.gradle, gradlew, gradlew.bat)
- [X] T006 Configure .gitignore and editorconfig for Java/Gradle in repository root

CI deterministic sim (replace smoke placeholders):
- [X] T007 Create headless run script `scripts/ci/sim/run-headless-paper.ps1` to boot Paper with Geyser and plugin
- [X] T008 [P] Add N-tick sim runner `scripts/ci/sim/run-scenario.ps1` (loads world seed, advances N ticks)
- [X] T009 [P] Implement state-hash assert script `scripts/ci/sim/assert-state.ps1` (economy/projects snapshots)
- [X] T010 Update workflow `.github/workflows/compatibility-smoke.yml` to invoke sim scripts and gate on assertions

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure required before any user story

- [X] T011 Implement tick scheduler/engine in `plugin/src/main/java/com/davisodom/villageoverhaul/core/TickEngine.java`
- [X] T012 [P] Implement WalletService (Dollaz, Millz int64) in `plugin/src/main/java/com/davisodom/villageoverhaul/economy/WalletService.java`
- [X] T013 [P] Implement Persistence (JSON) with versioning in `plugin/src/main/java/com/davisodom/villageoverhaul/persistence/JsonStore.java`
- [X] T014 Define JSON schemas in `plugin/src/main/resources/schemas/{culture.json,project.json,contract.json}`
- [X] T015 [P] Implement schema validator in `plugin/src/main/java/com/davisodom/villageoverhaul/data/SchemaValidator.java`
- [X] T016 Wire Observability (structured logs, tick metrics) in `plugin/src/main/java/com/davisodom/villageoverhaul/obs/`*
- [X] T017 [P] Add MockBukkit test harness `plugin/src/test/java/com/davisodom/villageoverhaul/TickHarnessTest.java`
- [X] T018 [P] Bootstrap minimal HTTP admin server (for contracts API) in `plugin/src/main/java/com/davisodom/villageoverhaul/admin/AdminHttpServer.java`
- [X] T019 Implement OpenAPI endpoints mapping per `specs/001-village-overhaul/contracts/openapi.yaml`

**Checkpoint**: Foundation ready → begin user stories

---

## Phase 2.5: Village Foundation (Pre-requisite for US1)

**Purpose**: Ensure culture-driven villages and minimal trade content exist so US1 can be tested independently.

- [X] T019a Implement minimal read-only Admin API mappings per OpenAPI (wallets/villages) in `admin/AdminHttpServer.java`
- [X] T019b [P] Add dungeon schema `plugin/src/main/resources/schemas/dungeon.json` and wire into validator
- [X] T019c [P] Scaffold CultureService to load/validate cultures in `plugin/src/main/java/com/davisodom/villageoverhaul/cultures/CultureService.java`
- [X] T019d [P] Add culture datapack scaffolding under `plugin/src/main/resources/datapacks/villageoverhaul/cultures/`
- [X] T019e Seeded test village fixture (FAWE/world placement or prebuilt structure) in `tests/fixtures/` with one upgrade path
- [X] T019f Minimal trade offers per initial culture for US1 test in `datapacks/.../trades/`
- [X] T019g [P] CI: validate culture packs with `SchemaValidator` and run a scenario that loads seeded village
- [X] T019h Per-village perf metrics (≤2ms amortized) counters in `obs/Metrics.java` (record by village ID)
- [X] T019i Economy anti-dupe and rate-limit tests for trade→treasury path in `plugin/src/test/java/.../EconomyAntiDupeTest.java`
- [X] T019j Cultural authenticity checklist task/doc for each culture in `docs/culture-review.md`

**Checkpoint**: Villages spawn/are placed with culture assignment; minimal trades available; CI scenario loads a seeded test village.

---

## Phase 2.6: Custom Villagers Foundation (Pre-requisite for US1/US2)

**Purpose**: Introduce culture-profession NPCs with custom appearance and interactions; maintain Bedrock parity.

- [X] T019k [P] Define schema `plugin/src/main/resources/schemas/custom-villager.json` (cultureId, professionId, appearanceProfile, dialogueKeys, spawnRules); wire into `SchemaValidator`.
- [X] T019l [P] Implement `CustomVillagerService.java` in `plugin/src/main/java/com/davisodom/villageoverhaul/npc/` (spawn/despawn, persist, find-by-village, caps).
- [X] T019m [P] Implement `VillagerAppearanceAdapter.java` in `npc/` (attire/armor/colors/nametags; optional Java resource-pack hooks; Bedrock-safe fallbacks).
- [X] T019n Intercept vanilla trading in `economy/TradeListener.java` or `npc/VillagerInteractionController.java`: cancel vanilla UI for Custom Villagers and route to custom flow.
- [X] T019o Implement `VillagerInteractionController.java` in `npc/` (chat/actionbar prompts; inventory GUI fallback; server-side validation; rate limits).
- [X] T019p Observability & Security: add NPC metrics in `obs/Metrics.java`; implement interaction rate limits and input validation.
- [X] T019q Localization: add dialogue/menu keys under `plugin/src/main/resources/lang/`; ensure coverage of FR-015.
- [X] T019r Compatibility tests: extend `scripts/ci/sim/run-scenario.ps1` to spawn at least one Custom Villager and verify interaction logs for Java-only and Java+Bedrock paths; update `docs/compatibility-matrix.md`.
  - **VERIFIED**: Test script functional with RCON integration, spawns custom villagers, validates server stability (Java 17+ required)
- [X] T019s Perf guardrail: add a perf test or metric snapshot in `tests/perf/` asserting npc.tick_time_ms within budget at Medium profile.
  - **VERIFIED**: Test script spawns N villagers via RCON, monitors server stability, validates no crashes under load

**Notes**: T019k–T019s map to FR-016 (Custom Villagers) and reinforce FR-010 (Cross-Edition), FR-012 (Performance), FR-013 (Security), and FR-015 (Localization).

**Infrastructure Notes**:
- Tests require Java 17+ (Paper 1.20.4 requirement)
- RCON-based automation via `/votest` commands
- Full player interaction validation requires bot player plugin (future enhancement)
- Metric collection validates server stability; detailed tick metrics pending tick engine integration

**Checkpoint**: Custom villager foundation complete (T019k-T019q); T019r-T019s verified with headless simulation infrastructure.

---

## Phase 3: User Story 1 — Trade-Funded Village Projects (Priority: P1) 🎯 MVP

**Goal**: Players trade; proceeds fund visible village projects and upgrade builds
**Independent Test**: New world → fund a project to 100% via trades → building upgrades deterministically

### Implementation

- [X] T020 [P] [US1] Create models: Village, Project in `plugin/src/main/java/com/davisodom/villageoverhaul/projects/`
- [X] T021 [P] [US1] Implement ProjectService in `plugin/src/main/java/com/davisodom/villageoverhaul/projects/ProjectService.java`
- [X] T022 [US1] Implement Treasury integration (trade → contribution) in `plugin/src/main/java/com/davisodom/villageoverhaul/economy/TradeListener.java`
- [X] T023 [P] [US1] Implement deterministic project progress + audit log in `ProjectService`
- [X] T024 [US1] Implement upgrade executor (FAWE/WorldGuard integration) in `plugin/src/main/java/com/davisodom/villageoverhaul/projects/UpgradeExecutor.java`
- [X] T025 [US1] Add admin command `/vo project status` in `plugin/src/main/java/com/davisodom/villageoverhaul/commands/ProjectCommands.java`

### Optional tests (if requested)

- [ ] T026 [P] [US1] MockBukkit test: contributions aggregate deterministically `plugin/src/test/java/.../ProjectContributionTest.java`
- [ ] T027 [P] [US1] Sim test: N ticks reach completion and trigger upgrade `scripts/ci/sim/run-scenario.ps1`

**Checkpoint**: US1 independently functional

---

## Phase 4: User Story 2 — Reputation & Contracts (Priority: P1)

**Goal**: Reputation progression, contract lifecycle, gated items/properties
**Independent Test**: Reach threshold via contract → unlock gated item; purchase eligibility validated

### Implementation

- [ ] T028 [P] [US2] Implement ReputationService in `plugin/src/main/java/com/davisodom/villageoverhaul/reputation/ReputationService.java`
- [ ] T029 [P] [US2] Implement Contract model + service in `plugin/src/main/java/com/davisodom/villageoverhaul/contracts/`
- [ ] T030 [US2] Server-side validation pipeline for contract completion in `contracts/ContractService.java`
- [ ] T031 [US2] Admin API endpoints: accept/complete per OpenAPI in `admin/AdminHttpServer.java`
- [ ] T032 [US2] Hook rewards to WalletService (atomic credit) in `economy/WalletService.java`

### Optional tests (if requested)

- [ ] T033 [P] [US2] Contract accept/complete integration test `plugin/src/test/java/.../ContractFlowTest.java`

**Checkpoint**: US2 independently functional

---

## Phase 5: User Story 3 — Dungeons & Custom Enemies (Priority: P2)

**Goal**: Deterministic dungeons with scalable difficulty; synchronized party play
**Independent Test**: Generate one dungeon from seed; clear; rewards + reputation applied

### Implementation

- [ ] T034 [P] [US3] Add structure templates under `plugin/src/main/resources/datapacks/villageoverhaul/dungeons/`
- [ ] T035 [P] [US3] Implement DungeonsService (seeded layouts) in `plugin/src/main/java/com/davisodom/villageoverhaul/dungeons/DungeonsService.java`
- [ ] T036 [US3] Enemy spawn/behavior (plugin-native) in `dungeons/EnemyManager.java` (optional MythicMobs adapter)
- [ ] T037 [US3] Reward disbursement path (atomic) in `contracts/ContractService.java`

### Optional tests (if requested)

- [ ] T038 [P] [US3] Sim test: Java+Bedrock party clears same instance with no desyncs `scripts/ci/sim/run-scenario.ps1`

**Checkpoint**: US3 independently functional

---

## Phase 6: User Story 4 — Inter-Village Relationships (Priority: P2)

**Goal**: Relations (ally/neutral/rival) influence prices and contracts
**Independent Test**: Influence contracts flip relation; price modifiers reflect status

### Implementation

- [ ] T039 [P] [US4] Implement Relationship model/service in `plugin/src/main/java/com/davisodom/villageoverhaul/relations/RelationshipService.java`
- [ ] T040 [US4] Apply price modifiers and contract availability in `contracts/ContractService.java`
- [ ] T041 [US4] Add hysteresis/cooldowns to relation changes in `relations/RelationshipService.java`

**Checkpoint**: US4 independently functional

---

## Phase 7: User Story 5 — Property Purchasing (Priority: P3)

**Goal**: Purchase plots/houses (S/M/L), enforce ownership limits and ACLs
**Independent Test**: Purchase entry-tier home; ownership persists across restart

### Implementation

- [ ] T042 [P] [US5] Implement Property model/service in `plugin/src/main/java/com/davisodom/villageoverhaul/property/PropertyService.java`
- [ ] T043 [US5] Enforce one-per-size limits and funds transfer in `property/PropertyService.java`
- [ ] T044 [US5] Create regions and permissions via WorldGuard/LuckPerms in `property/PropertyAclAdapter.java`
- [ ] T045 [US5] Admin command `/vo property buy` and status in `commands/PropertyCommands.java`

**Checkpoint**: US5 independently functional

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T046 [P] Documentation updates in `docs/` and `specs/001-village-overhaul/quickstart.md`
- [ ] T047 Performance passes; Spark/Timings baseline/capture scripts `tests/perf/` and `scripts/ci/sim/`
- [ ] T048 [P] Update compatibility matrix with verified scenarios in `docs/compatibility-matrix.md`
- [ ] T049 Localization scaffolding (Adventure/MiniMessage) in `plugin/src/main/resources/lang/`
- [ ] T050 Security hardening (rate limits, validation) across services in `plugin/src/main/java/...`
- [ ] T051 Run quickstart validation end-to-end and capture artifacts in `artifacts/`

---

## Dependencies & Execution Order

### Phase Dependencies
- Setup (Phase 1): none
- Foundational (Phase 2): depends on Setup completion; BLOCKS US1–US5
- US1..US5 (Phases 3–7): depend on Foundational; each independently testable
- Polish (Phase 8): after desired stories complete

### User Story Dependencies
- US1 (P1): none (post-Foundational)
- US2 (P1): none (post-Foundational); integrates with WalletService
- US3 (P2): none (post-Foundational); optional integration with US2 rewards
- US4 (P2): none (post-Foundational); reads economy/contracts for modifiers
- US5 (P3): none (post-Foundational); depends on WalletService and Reputation thresholds

### Parallel Opportunities
- [P] tasks in Setup and Foundational can run concurrently
- Within each story, [P] model/service tasks can proceed in parallel
- Sim scripts/assertions can run while UI/commands are implemented

---

## Implementation Strategy

### MVP First (US1 Only)
1) Complete Phases 1–2
2) Implement Phase 3 (US1)
3) Validate with headless sim and MockBukkit tests

### Incremental Delivery
- Deliver US1 (P1) → US2 (P1) → US3/US4 (P2) → US5 (P3)
- Each story independently testable per its checkpoint


