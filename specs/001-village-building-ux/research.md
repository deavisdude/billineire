# Research — Village Overhaul (Structures First)

Date: 2025-11-05
Spec: ../001-village-overhaul/spec.md

## Decisions

- Structure Representation: WorldEdit API is the standard surface for structure manipulation.
  Prefer FAWE when present for performance. Use Sponge Schematic v2 as the canonical on-disk
  format. Determinism ensured by fixed ordering and seeds.
- Site Validation: Validate foundation solidity under footprint, interior air-space at entrances/rooms, and collision
  clearance. Abort or re-seat on failure. Minor localized terraforming allowed (light grading/filling, vegetation
  trimming) to achieve natural placement; forbid large artificial platforms/cliff cuts.
- Path Generation: 2D heightmap A* with terrain-aware costs; emit minimal path with light smoothing (steps/slabs);
  avoid block spam and unnatural boardwalks.
- Main Building Designation: One per village per culture, persisted in village metadata. Selection uses the culture's
  `mainBuildingStructureId` field (e.g., "building_roman_forum" for Roman) to identify the designated structure. If not 
  specified, defaults to the first structure in the culture's `structureSet`. The main building is ALWAYS placed first 
  during village generation to guarantee its presence, while other structures are randomly selected and may fail placement.
- Determinism: Seed = H(worldSeed, featureSeed, villageId); no reliance on nondeterministic iteration; snapshot inputs
  in logs for replay.
- Async Placement, Chunk-Gating & Budgeting: Prepare large structures off-thread. Maintain a
  placement queue with deterministic ordering (by layer and row). Commit small batches on the main
  thread aligned to chunk boundaries. Never mutate world off-thread.
- Observability: [STRUCT] logs for placement begin/seat/re-seat/abort; counters for placements, retries, path coverage,
  main-building tagging; debug toggles.
- Parity (Java/Bedrock): Use Adventure API for signage text; avoid client-only visuals; ensure functional parity.
- Protections: If WorldGuard present, treat regions disallowing placement as invalid sites.

### Village Site Selection & Borders

- Inter-Village Minimum Distance: Enforce a configurable border-to-border spacing (default 200 blocks)
  between villages. Enforcement is bidirectional and measured using dynamic borders, not just centers.
- Spawn Proximity: First/early villages are placed near (but not at) world spawn to improve player onboarding and reduce travel.
- Nearest-Neighbor Bias: For subsequent villages, the search chooses a site as close as possible to an
  existing village border while respecting the minimum distance.
- Dynamic Borders: Village borders expand deterministically with new construction projects (based on
  aggregated building footprints). Expansion is clipped in directions where a neighbor’s border exists within
  `minVillageSpacing`. Borders are persisted and exposed to UX (e.g., map/signage).
- Determinism & Performance: Searches seeded from world seed; chunk-gated scans; cheap axis-aligned border
  representation for intersection checks, with potential future polygonal refinement.

## NPC Builder Architecture (Minecolonies Pattern)

State machine with persisted checkpoints and visible progress:

- IDLE → WALKING_TO_BUILDING → REQUESTING_MATERIALS → GATHERING_MATERIALS → CLEARING_SITE →
  PLACING_BLOCKS → COMPLETING → STUCK (recovery)
- Block placement is row-by-row, layer-by-layer to provide visible progress to players.
- Material manager coordinates requests/pickups/consumption from storage; operations are
  server-authoritative and logged.

Pathfinding limits and performance controls:

- Treat local pathfinding radius as ~10 blocks; use waypoint navigation for longer routes.
- Cache path segments; invalidate cache on terrain changes only.
- Cap concurrent pathfinding operations to protect the main tick.

Integration with structure generation:

- Prefer registering structures with a popular plugin (e.g., CustomStructures) when appropriate,
  and integrate with Paper’s structure generation pipeline.

## Rationale

- WorldEdit/FAWE enables faster, reliable edits while keeping determinism; fallback path ensures no hard dependency. Terraforming policy
  balances natural aesthetics with predictable, safe edits.
- A* on a heightmap is simple, fast enough, and easy to smooth with slabs/steps without heavy terrain changes.
- Seed composition ensures reproducible outputs per world/village while allowing diversity across villages.
 - Inter-village spacing preserves readable, navigable world layouts and prevents village overlap.
   Spawn-proximal initial placement improves early gameplay; nearest-neighbor bias keeps world scale compact
   without violating spacing rules.

## Alternatives Considered

- Vanilla structure templates/Jigsaw: Powerful but heavier integration and less control for small, culture-specific
  sets; phased adoption later is possible.
- Pure WorldEdit dependency: Faster but creates hard dependency; fallback would be missing.
  → Decision: Use WorldEdit API surface with FAWE preferred but keep a minimal fallback path.
- Voronoi-based road network: Overkill for initial scope; A* per-pair with MST selection suffices.

## Critical Performance Consideration

- Large structure placement on the main thread causes server lag. Use asynchronous preparation and
  main-thread batched commits only. Validate via headless harness logs and tick-time counters.

## Open Items Resolved

- Minor Terraforming Allowed: Yes, localized and natural-looking only.
- Contracts: Represented as admin test endpoints in OpenAPI for planning/testing; actual interface is plugin commands.
