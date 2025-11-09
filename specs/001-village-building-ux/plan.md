# Implementation Plan: Village Overhaul — Structures First

**Branch**: `001-village-building-ux` | **Date**: 2025-11-05 | **Spec**: specs/001-village-overhaul/spec.md
**Input**: Unified feature specification from `specs/001-village-overhaul/spec.md`

**Note**: Planning targets structure generation and path network first to unblock the project, with
onboarding (greeter/signage) following after main-building designation.

## Summary

Primary requirement: Generate culture-defined village structures with grounded placement (no
floating/embedded), connect key buildings with paths, enforce inter-village minimum spacing with
dynamic borders, and designate exactly one main building per
village (per culture) as the onboarding anchor. Technical approach: data-driven structures with
site validation (foundation + interior air checks), minor localized terraforming allowed for
natural placement, 2D path generation with light smoothing (steps/slabs) and no block spam, all
deterministic from world/feature seeds and chunk-gated to respect tick budgets.

## Technical Context

**Language/Version**: Java 21 (Paper 1.20+); optional Kotlin 1.9 (JVM 21)  
**Primary Dependencies**: Paper API; WorldEdit API (FAWE if present for performance) for
  structure load/rotate/paste; Adventure API (signage/messages); Jackson/Gson for JSON; WorldGuard
  (optional, placement guards); LuckPerms/Vault present in repo but not required for structure gen  
**Storage**: World-save for placed blocks; plugin JSON for cultures/structure sets; persistent
  village metadata (main-building id, path network) via plugin storage  
**Testing**: Headless Paper harness (scripts/ci/sim), RCON-driven test scripts, JUnit for core
  logic; deterministic simulation tests for structures/paths/main-building  
**Target Platform**: Paper server (Java-only and Java+Bedrock-bridge targets)  
**Project Type**: Single plugin (Gradle)  
**Performance Goals**: Per Constitution: 20 TPS target, p95 ≤ 8ms, p99 ≤ 12ms aggregate; per-village
  average ≤ 2ms amortized; structure/place/path tasks chunk-gated and batched; large structure
  preparation strictly asynchronous with main-thread batched commits only  
**Constraints**: Deterministic outputs; minor, localized terraforming allowed; no main-thread
  blocking beyond budget; no off-thread world mutations; async preparation for large structures;
  visible row/layer progress; region-protection aware  
**Scale/Scope**: Up to 50 loaded villages, 500 active villagers, and many structures/paths active
  concurrently in the Medium profile

Configuration highlights:
- village.minBuildingSpacing: default 8 blocks (inter-building)
- village.minVillageSpacing: default 200 blocks (border-to-border, bidirectional enforcement)
  - First/early village search biased near (but not at) spawn
  - Subsequent villages biased to be as close as possible to an existing village while respecting
    minVillageSpacing
  - Borders are dynamic and expand with construction; expansions are clipped where a neighbor’s
    border is within minVillageSpacing

## Constitution Check

Must-pass gates and how we satisfy them for this phase:

- Cross-Edition Compatibility: Signage and greeter use Adventure API text; provide Bedrock-parity
  output (no client-only visuals required). Validate on Java-only and Java+Bedrock harnesses.
- Deterministic Multiplayer Sync: All placement decisions derive from world/feature seeds + inputs;
  path network and main-building selection repeat under fixed seeds; provide deterministic tests.
- Performance Budgets: Structure seating and path generation chunk-gated; long operations batched
  across ticks; measure tick-time and memory deltas under Medium profile in CI.
- Modularity: Implement under "worldgen/structures" and "villages" modules within plugin; expose
  minimal service APIs (StructureService, VillagePlacementService) without cyclic deps.
- Save/Migration: Persist main-building id and path metadata with a schema version tag; bump if
  structure metadata format changes; backup + verify on migration.
- Observability: Add [STRUCT] logs for seat/abort/re-seat; metrics for placements, retries, path
  connectivity, and main-building designation; debug flags to enable/trace.
- Cultural/Balance: Culture data references structureSet and main-building type; review for each
  culture; ensure signage is localized.
- Security/Anti-Exploit: Respect region protections (WorldGuard) when present; validate inputs for
  admin/test commands; rate-limit greeter triggers.

- Scripting & CI Portability: CI scripts already PS 5.1 compatible; ASCII-only logs; single-quoted
  regex [0-9] classes; readiness via substring 'Done'.
- Village Building & UX: Grounded placement; inter-building paths; one main building per village;
  signage for projects/requirements; greeter behavior on main-building entry.

- Inter-Village Spacing & Borders: Enforce configurable minimum border-to-border distance between
  villages (default 200). First/early villages start near spawn (not at exact spawn), and later
  villages are placed as close as possible to existing borders without violating the minimum.
  Borders expand dynamically with construction and are clipped near neighboring borders.

- Structure Integration & NPC Construction: Use WorldEdit/FAWE for structure load/rotate/paste
  with footprint validation. All large structure preparation is asynchronous; block mutations are
  committed in small, main-thread batches. NPC builder follows a deterministic state machine
  (IDLE → WALKING_TO_BUILDING → REQUESTING_MATERIALS → GATHERING_MATERIALS → CLEARING_SITE →
  PLACING_BLOCKS → COMPLETING → STUCK) with persisted progress and visible row/layer placement.
  Material manager coordinates inventories between builders and storage. Pathfinding respects a
  local radius (~10 blocks), uses waypoint segments for longer routes, caches paths, invalidates on
  terrain change, and caps concurrent planners. Prefer structure registration via a popular
  registry (e.g., CustomStructures) and integrate with Paper’s structure generation pipeline where
  appropriate.

## Project Structure

### Documentation (this feature)

```text
specs/001-village-building-ux/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output (admin harness API for tests)
└── tasks.md             # Phase 2 (/speckit.tasks will generate)
```

### Source Code (repository root)

```text
plugin/
├── src/
│  ├── main/java/com/davisodom/...        # services, commands, placement, pathing
│  └── main/resources/                  # cultures/, schemas/, plugin.yml
└── build.gradle

scripts/ci/sim/                         # headless Paper harness + RCON helpers
```

**Structure Decision**: Single plugin project. New/expanded services:
- StructureService: load/rotate/paste structures via WorldEdit/FAWE, validate sites, minor terraforming,
  async preparation with main-thread batched commits
- PathService: connect key buildings with natural paths (smoothing allowed)
- VillagePlacementService: drive end-to-end seating + main-building designation, village site search
  with inter-village spacing enforcement (border-to-border), spawn/nearest-neighbor bias
- BuilderService: state machine orchestration (see Research) and visible row/layer placement
- MaterialManager: coordinate builder inventory with storage/warehouse (server-authoritative)
- TestCommands: admin endpoints to simulate generation and validate outputs

### Planning & Task Dependencies (per Constitution)

- WorldEdit integration layer → Structure loading system → Placement engine
- NPC base class → State machine → Builder AI → Material manager
- Village data model → Village manager → Expansion system
- Pathfinding util → Navigator → Builder movement
- Configuration system → All subsystems

## Complexity Tracking

No Constitution violations planned for this phase.

