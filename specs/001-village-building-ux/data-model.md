# Data Model — Village Overhaul (Structures First)

Spec: ../001-village-overhaul/spec.md
Date: 2025-11-05

## Entities

### Village
- id: UUID
- cultureId: string
- location: world + center (x,z)
- mainBuildingId: UUID (nullable until set)
- pathNetworkId: UUID
- projects: [ProjectRef]
- schemaVersion: int
 - asyncPlacementEnabled: bool
 - structureRegistryRef: string (e.g., CustomStructures key) (optional)
 - minBuildingSpacing: int (loaded from config at creation time)
 - minVillageSpacing: int (loaded from config at creation time)
 - border: { minX: int, maxX: int, minZ: int, maxZ: int } (dynamic, expands with construction)
 - lastBorderUpdateTick: long
 - borderExpansionBlockedDirs: set<enum {NORTH,SOUTH,EAST,WEST}> (neighbor proximity clipping)

### Building
- id: UUID
- villageId: UUID
- typeId: string (from culture structureSet)
- footprint: bounds (min/max x,y,z) + anchor
- placedAt: world + origin (x,y,z)
- validation: { foundationOk: bool, interiorAirOk: bool }
 - pasteProvider: enum { WORLDEDIT, FAWE, VANILLA }
 - placementMode: enum { ASYNC_QUEUED, DIRECT_SMALL }
 - progress: { layer: int, row: int, percent: double }

### MainBuilding (logical designation)
- buildingId: UUID (references Building)
- designationAt: timestamp
 - isSpawnProximal: bool (true when first village near spawn)

### PathNetwork
- id: UUID
- villageId: UUID
- nodes: [{ id, x, y, z }]
- edges: [{ a, b, cost }]
- traversableBlocks: set<string>
 - waypoints: [{ id, x, y, z }]
 - plannerConcurrencyCap: int
 - registeredBuildingBounds: [ { villageId, minX, maxX, minZ, maxZ } ] (obstacles for pathfinding)

### ProjectSignage
- id: UUID
- villageId: UUID
- mainBuildingId: UUID
- contentRef: string (rendered via Adventure API)
- lastRefreshedAt: timestamp

### Builder
- id: UUID
- villageId: UUID
- targetBuildingId: UUID
- state: enum { IDLE, WALKING_TO_BUILDING, REQUESTING_MATERIALS, GATHERING_MATERIALS, CLEARING_SITE, PLACING_BLOCKS, COMPLETING, STUCK }
- lastCheckpointAt: timestamp
- inventory: map<itemId, count>
- pathCacheKey: string (nullable)
- currentWaypointIndex: int (nullable)

### MaterialRequest
- id: UUID
- builderId: UUID
- villageId: UUID
- items: map<itemId, count>
- status: enum { PENDING, ALLOCATED, PICKED_UP, CONSUMED, CANCELLED }
- warehouseRef: string (chest/location id)

### PlacementQueue
- id: UUID
- buildingId: UUID
- entries: [ { x, y, z, blockId, meta, layer, row } ] (ordered deterministically)
- status: enum { PREPARING, READY, COMMITTING, COMPLETE, ABORTED }
- batchSize: int (per-tick)

### GreeterTrigger
- id: UUID
- villageId: UUID
- mainBuildingId: UUID
- radius: double
- cooldownSeconds: int
- lastTriggeredBy: map<playerId, timestamp>

## Relationships
- Village 1..* Building
- Village 1..1 PathNetwork
- Village 0..1 MainBuilding
 - Village (neighbors): implicit relationship derived from overlapping or near (within 2*minVillageSpacing) border proximity
- MainBuilding 1..1 Building
- Village 0..1 ProjectSignage (current view)
- Village 0..* Builder
- Builder 0..* MaterialRequest
- Building 1..1 PlacementQueue (while under construction)

## Validation Rules
- Exactly one MainBuilding per Village
- Building validation MUST be true (foundationOk && interiorAirOk) to be retained
- Paths MUST connect designated key buildings; avoid blocked nodes
- GreeterTrigger rate-limited per-player
 - Large structure placement MUST use ASYNC_QUEUED mode with main-thread batched commits only
 - Builder state transitions MUST follow the defined state machine; progress checkpoints persisted
 - Pathfinding searches SHOULD be local (≈10 blocks). Longer routes MUST use waypoint segments,
	 cache paths, invalidate on terrain changes, and respect plannerConcurrencyCap
 - Village border expansions MUST NOT violate minVillageSpacing vs any neighbor (border-to-border)
 - First village MUST be within configurable spawn proximity range but not centered exactly on spawn (avoid spawn griefing)
 - Subsequent village placements MUST select the nearest valid site (≥ minVillageSpacing) to at least one existing village border
 - Border clipping directions MUST reflect neighbor proximity; expansion attempts into blocked directions aborted or deferred

## Notes
- Persist schemaVersion for forward-only migration safety (see Constitution)
 - WorldEdit API is the standard for structure manipulation (FAWE preferred when available)
 - Prefer registering structures with a shared registry (e.g., CustomStructures) when integrated
 - Border representation kept axis-aligned for deterministic computation and cheap intersection
 - Potential future enhancement: polygonal borders once path networks and terrain shaping justify complexity
