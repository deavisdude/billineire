# T087 Collision Diagnostics Report

Date: 2026-01-20

## Summary
Headless runs with large bounds show extreme collision rejection rates (~99%) after a few initial placements. New diagnostics capture per-candidate AABB checks, spacing buffers, and VolumeMask expansions to compare successful vs failed candidates.

## Root Cause (Primary)
**Rotation mapping mismatch between candidate AABB computation and actual placement rotation**:

- `VillagePlacementHelper.computeRotatedAABB()` and `StructureServiceImpl.computeAABB()` both implement WorldEdit’s clockwise Y-rotation mapping for 90° and 270°: `(x,z) -> (z,-x)` and `(x,z) -> (-z,x)`.
- `VillagePlacementServiceImpl.computeRotatedAABB()` uses the *inverse* mapping for 90°/270°: `(x,z) -> (-z,x)` and `(x,z) -> (z,-x)`.

This mismatch causes candidate AABBs to be rotated opposite of the real placement. When spacing buffers are applied to existing masks, the candidate AABB appears to overlap more frequently than it should, inflating collision rejections during candidate search.

### Evidence
- Candidate AABB collision checks in `VillagePlacementServiceImpl` use the mismatched rotation mapping.
- Placement AABBs in `StructureServiceImpl` use the WorldEdit-consistent mapping.
- New collision artifacts show candidate AABBs shifted into the opposite quadrant when rotation is 90° or 270°, producing overlap against expanded masks that are not actually violated by the placed structure.

## Secondary Observations
- Candidate filtering uses 2D XZ overlap while placement validation uses 3D overlap against `VolumeMask.expand(...)` (which expands Y as well). This is not necessarily wrong for grounded structures, but it makes collision behavior harder to reason about and can obscure root-cause analysis in logs.

## Reproduction Steps
1. Run a large-bounds headless scenario:
   - `scripts/ci/sim/run-scenario.ps1 -Ticks 400 -Seed <failing-seed> -MaxBoundsRadiusBlocks 256`
2. Inspect `test-server/logs/latest.log` for `[STRUCT][COLLISION-DIAG]` lines and `ZERO-PLACEMENT` summaries.
3. Review collision artifacts in `test-server/logs/collision_diag_*.json` and compare:
   - Successful candidates (no overlaps)
   - Failed candidates with rotation 90° or 270° showing shifted AABB positions

## Recommended Mitigation Plan (No Immediate Fix Applied)
1. **Align rotation mapping**
   - Use `VillagePlacementHelper.computeRotatedAABB()` (or shared utility) inside `VillagePlacementServiceImpl` to match WorldEdit’s rotation mapping.
2. **Add rotation-consistency tests**
   - New unit test validating that candidate AABB calculations match placement AABB results for 0/90/180/270°.
3. **Standardize collision checks**
   - Decide on 2D vs 3D collision semantics and apply consistently across candidate filtering and placement validation.

## Follow-up Tasks
- **T087a**: Unify rotation mapping for candidate AABB checks with WorldEdit rotation.
- **T087b**: Add unit test ensuring candidate AABB matches placement AABB for all rotations.
- **T087c**: Standardize collision overlap semantics (2D vs 3D) and document spacing buffer behavior.
