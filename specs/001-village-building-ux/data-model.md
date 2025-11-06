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

### Building
- id: UUID
- villageId: UUID
- typeId: string (from culture structureSet)
- footprint: bounds (min/max x,y,z) + anchor
- placedAt: world + origin (x,y,z)
- validation: { foundationOk: bool, interiorAirOk: bool }

### MainBuilding (logical designation)
- buildingId: UUID (references Building)
- designationAt: timestamp

### PathNetwork
- id: UUID
- villageId: UUID
- nodes: [{ id, x, y, z }]
- edges: [{ a, b, cost }]
- traversableBlocks: set<string>

### ProjectSignage
- id: UUID
- villageId: UUID
- mainBuildingId: UUID
- contentRef: string (rendered via Adventure API)
- lastRefreshedAt: timestamp

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
- MainBuilding 1..1 Building
- Village 0..1 ProjectSignage (current view)

## Validation Rules
- Exactly one MainBuilding per Village
- Building validation MUST be true (foundationOk && interiorAirOk) to be retained
- Paths MUST connect designated key buildings; avoid blocked nodes
- GreeterTrigger rate-limited per-player

## Notes
- Persist schemaVersion for forward-only migration safety (see Constitution)
