# Data Model — Village Overhaul

Date: 2025-11-04
Branch: 001-village-overhaul

## Entities

### Culture
- id: string
- name: string (localized)
- structureSet: ids[]
- professionSet: ids[]
- styleRules: object
- validation:
  - name unique; structure/profession references exist

### Village
- id: string
- cultureId: Culture.id
- wealthMillz: int64 (>=0)
- population: int
- reputation: map<PlayerId,int>
- projects: Project[]
- relations: RelationshipEdge[]
- validation:
  - reputation scores bounded; culture exists

### Project
- id: string
- villageId: Village.id
- buildingRef: string
- costMillz: int64 (>0)
- progressMillz: int64 (>=0)
- contributors: map<PlayerId,int64>
- status: enum(pending, active, complete)
- unlockEffects: string[]
- transitions:
  - pending→active on publish; active→complete when progress>=cost

### TradeOffer
- id: string
- inputs: ItemStack[]
- outputs: ItemStack[] | {currency: millz}
- reputationReq: int
- cultureTags: string[]
- dynamicPricing: bool

### CurrencyWallet
- owner: PlayerId | Village.id
- balanceMillz: int64 (>=0)
- txLog: Tx[]
- methods: credit(millz), debit(millz), format(), breakdown()
- invariants:
  - integer-only; no negative balances; log for every mutation

### Contract
- id: string
- type: enum(fetch, defense, dungeon)
- objectives: Objective[]
- rewards: {currencyMillz:int64, items?:ItemStack[]}
- timers: {start:instant, expires?:instant}
- issuerVillageId: Village.id
- participants: PlayerId[]
- status: enum(available, accepted, completed, expired)
- transitions:
  - available→accepted on claim; accepted→completed when server validates objectives; accepted→expired on timeout

### DungeonInstance
- id: string
- seed: long
- layoutRef: string
- difficultyScale: float
- state: enum(spawned, active, cleared)
- rewardsIssued: PlayerId[]

### EnemyFaction
- id: string
- traits: object
- spawnRules: object
- lootTables: ids[]

### RelationshipEdge
- villageA: Village.id
- villageB: Village.id
- status: enum(ally, neutral, rival)
- score: int (bounded; hysteresis)
- lastChangedAt: instant
- modifiers: object

### Property
- id: string
- villageId: Village.id
- type: enum(plot, house)
- size: enum(S, M, L)
- lotBounds: AABB/world-region
- priceMillz: int64
- furnishings: ItemStack[] | null
- owner: PlayerId | null
- permissions: PermissionSet
- constraints:
  - ownership limit: one of each size per player for plots and houses

### PlayerProfile
- playerId: UUID
- reputation: map<Village.id,int>
- ownedProperties: Property.id[]
- lastContributionAt: instant

## Relationships
- Village 1..* Project
- Village 0..* Property
- Player 0..* Property
- Village —(undirected)→ Village via RelationshipEdge
- Village 0..* Contract

## Validation Rules
- Integer math in Millz for all currency fields; no floats.
- Contract rewards must be disbursed atomically; tx logged.
- Project progress cannot exceed cost; clamp and complete transactionally.
- Property purchase enforces limits and atomic transfer of funds and ownership.

## Versioning & Migration
- Schema version per file; forward-only migrations with backup+verify.
