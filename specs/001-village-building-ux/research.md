# Research — Village Overhaul (Structures First)

Date: 2025-11-05
Spec: ../001-village-overhaul/spec.md

## Decisions

- Structure Representation: Prefer FAWE + Sponge Schematic v2 for performance when FAWE is present; fallback to
  Paper API block placement from a compact JSON template describing block palette, anchor, and footprint. Determinism
  ensured by fixed ordering and seeds.
- Site Validation: Validate foundation solidity under footprint, interior air-space at entrances/rooms, and collision
  clearance. Abort or re-seat on failure. Minor localized terraforming allowed (light grading/filling, vegetation
  trimming) to achieve natural placement; forbid large artificial platforms/cliff cuts.
- Path Generation: 2D heightmap A* with terrain-aware costs; emit minimal path with light smoothing (steps/slabs);
  avoid block spam and unnatural boardwalks.
- Main Building Designation: One per village per culture, persisted in village metadata; consistent selection from
  structure set using seeded choice + constraints (centrality/proximity to paths).
- Determinism: Seed = H(worldSeed, featureSeed, villageId); no reliance on nondeterministic iteration; snapshot inputs
  in logs for replay.
- Chunk-Gating & Budgeting: Split large placements into batches aligned to chunk boundaries; schedule over ticks; never
  mutate world off-thread.
- Observability: [STRUCT] logs for placement begin/seat/re-seat/abort; counters for placements, retries, path coverage,
  main-building tagging; debug toggles.
- Parity (Java/Bedrock): Use Adventure API for signage text; avoid client-only visuals; ensure functional parity.
- Protections: If WorldGuard present, treat regions disallowing placement as invalid sites.

## Rationale

- FAWE enables faster edits while keeping determinism; fallback path ensures no hard dependency. Terraforming policy
  balances natural aesthetics with predictable, safe edits.
- A* on a heightmap is simple, fast enough, and easy to smooth with slabs/steps without heavy terrain changes.
- Seed composition ensures reproducible outputs per world/village while allowing diversity across villages.

## Alternatives Considered

- Vanilla structure templates/Jigsaw: Powerful but heavier integration and less control for small, culture-specific
  sets; phased adoption later is possible.
- Pure WorldEdit dependency: Faster but creates hard dependency; fallback would be missing.
- Voronoi-based road network: Overkill for initial scope; A* per-pair with MST selection suffices.

## Open Items Resolved

- Minor Terraforming Allowed: Yes, localized and natural-looking only.
- Contracts: Represented as admin test endpoints in OpenAPI for planning/testing; actual interface is plugin commands.
