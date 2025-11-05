# Feature Specification: Minecraft Village Overhaul

**Feature Branch**: `001-village-overhaul`  
**Created**: 2025-11-04  
**Status**: Draft  
**Input**: User description: "Overhaul Minecraft villages similar to the Millénaire mod but for modern Minecraft. Include diverse cultures for village designs and inhabitants. Enable players to trade with villagers to earn currency and buy unique items. Player trades directly contribute to village building and expansion goals. Villages upgrade buildings as they advance and grow wealthier. Players gain reputation through trading and completing village contracts. Villages have relationships with each other that players can influence. Generate custom enemies and dungeons, with contracts from villages to clear them for generous rewards. Allow players to purchase property or fully furnished homes in villages if they have enough money and reputation."

## User Scenarios & Testing *(mandatory)*

<!--
  IMPORTANT: User stories should be PRIORITIZED as user journeys ordered by importance.
  Each user story/journey must be INDEPENDENTLY TESTABLE - meaning if you implement just ONE of them,
  you should still have a viable MVP (Minimum Viable Product) that delivers value.
  
  Assign priorities (P1, P2, P3, etc.) to each story, where P1 is the most critical.
  Think of each story as a standalone slice of functionality that can be:
  - Developed independently
  - Tested independently
  - Deployed independently
  - Demonstrated to users independently
-->

### User Story 1 - Trade-Funded Village Projects (Priority: P1)

Players trade with villagers using a currency economy; proceeds fund village "projects" (e.g., house upgrade,
blacksmith expansion). Players see project goals, contribution progress, and resulting building upgrades.

**Why this priority**: Establishes core gameplay loop that ties player actions to visible village growth.

**Independent Test**: On a new world with a seeded test village, complete trades to 100% a project and observe the
building upgrade without any other systems enabled (no reputation or dungeons required).

**Acceptance Scenarios**:

1. Given a village with an active project, When the player completes trades worth the project cost, Then the
  project completes and the corresponding building upgrades in-world.
2. Given multiple contributors, When all contribute cumulatively to the project, Then contributions are aggregated
  deterministically server-side with a progress audit log.

---

### User Story 2 - Reputation & Contracts (Priority: P1)

Players earn village reputation through trading and completing contracts posted by the village (e.g., provision
deliveries, defense events, dungeon clearing). Higher reputation unlocks unique items, professions, and property
purchase eligibility.

**Why this priority**: Reputation provides progression and access gating for advanced content and housing.

**Independent Test**: With trading and contracts enabled, reach a reputation threshold to unlock a gated item and
verify purchase eligibility without enabling inter-village relations.

**Acceptance Scenarios**:

1. Given neutral reputation, When the player completes an A-tier contract, Then reputation increases per rules,
  unlocking at least one new trade.
2. Given sufficient reputation, When the player attempts to buy a property, Then purchase UI and ownership transfer
  succeed with server-side validation.

---

### User Story 3 - Dungeons & Custom Enemies (Priority: P2)

Villages issue contracts to clear nearby procedurally generated dungeons populated by custom enemies. Difficulty and
rewards scale with player/team progression and village wealth.

**Why this priority**: Provides compelling PvE loops that tie economy and reputation to combat content.

**Independent Test**: Generate a single dungeon instance from a seed, clear it, and validate reward delivery and
village reputation changes without enabling inter-village relations.

**Acceptance Scenarios**:

1. Given a dungeon contract, When the dungeon is cleared (boss defeated and objective markers complete), Then the
  village disburses contracted rewards and grants reputation.
2. Given Bedrock and Java clients in the same party, When they engage the same dungeon instance, Then all state
  changes (mob spawns, loot, completion) remain synchronized.

---

### User Story 4 - Inter-Village Relationships (Priority: P2)

Villages maintain relationships (ally, neutral, rival) that players can influence via contracts and trade routes.
Relationships affect prices, contract availability, and event frequency.

**Why this priority**: Creates a dynamic world economy and meaningful long-term choices.

**Independent Test**: With two villages loaded, complete influence contracts until their relationship changes and
verify price modifiers without enabling property purchases.

**Acceptance Scenarios**:

1. Given two neutral villages, When the player completes influence contracts for Village A against Village B, Then
  the relationship shifts toward rival and prices adjust accordingly.
2. Given allied villages, When a trade route is established, Then shared prosperity triggers cross-village project
  opportunities.

---

### User Story 5 - Property Purchasing (Priority: P3)

Players with sufficient currency and reputation can purchase lots or fully furnished homes inside villages and gain
storage plus cosmetic customization rights.

**Why this priority**: Provides long-term goals and a tangible sense of belonging in a chosen village.

**Independent Test**: With reputation enabled, purchase an entry-tier home and verify ownership persistence across
server restarts.

**Acceptance Scenarios**:

1. Given a for-sale home and sufficient reputation/currency, When the player purchases the home, Then ownership is
  recorded server-side and the deed item is issued.
2. Given a purchased home, When the server restarts, Then ownership persists and access controls are enforced.

---

[Add more user stories as needed, each with an assigned priority]

### Edge Cases

<!--
  ACTION REQUIRED: The content in this section represents placeholders.
  Fill them out with the right edge cases.
-->

- Player trades during chunk unload/server restart: contributions MUST be atomic and idempotent.
- Bedrock clients lacking certain UI widgets: provide simplified UIs and parity fallbacks.
- Contract griefing attempts (e.g., kill stealing, loot dupes): server authority and anti-exploit checks apply.
- Dungeon generation near protected areas: enforce worldgen safe zones and re-roll rules.
- Multiple players racing to buy the same property: first-commit wins via server-side transaction lock.
- Inter-village relation oscillation: apply hysteresis/cooldowns to avoid flip-flopping.

## Requirements *(mandatory)*

<!--
  ACTION REQUIRED: The content in this section represents placeholders.
  Fill them out with the right functional requirements.
-->

### Functional Requirements

- **FR-001 (Cultures)**: The system MUST ship with multiple distinct village cultures (build styles, professions,
  trades, names, localization hooks). Initial launch cultures: Roman, Viking, Middle Ages, Native American, British
  Colonial, Egyptian, Chinese.
- **FR-002 (Economy)**: Trading MUST earn currency and directly contribute to active village projects; contributions
  aggregate server-side and are audit-able. Base currency: Dollaz. Denominations: 100 Millz = 1 Billz; 100 Billz = 1
  Trills (all are denominations of Dollaz). Player balances MUST auto-condense into the highest denominations and
  auto-make change losslessly in UI and transactions.

- **FR-002a (Denomination Mechanics)**: Currency arithmetic MUST be deterministic and lossless using integer math in
  the smallest unit (Millz). No floating point. All deposits/withdrawals/fees use Millz internally; display logic
  formats as Trills/Billz/Millz with automatic condensation and change-making.
- **FR-003 (Projects/Upgrades)**: Villages MUST surface visible project goals with costs and upgrade buildings when
  fully funded.
- **FR-004 (Reputation)**: Players MUST gain/lose reputation via trades and contracts, unlocking items, professions,
  and property eligibility at thresholds.
- **FR-005 (Contracts)**: Villages MUST publish contracts (fetch, defense, dungeon) with clear objectives, timers,
  and rewards; completion MUST be validated server-side.
- **FR-006 (Dungeons/Enemies)**: The system MUST generate deterministic, seed-based dungeons with custom enemies and
  loot tables; difficulty and rewards scale with progression.
- **FR-007 (Relationships)**: Villages MUST track relationships (ally/neutral/rival) and expose influence levers to
  players; relations affect prices and contract availability.
- **FR-008 (Property)**: Players with sufficient reputation/currency MUST be able to buy lots/homes; ownership MUST
  persist and enforce access controls. Ownership limits: at most one of each size for plots (S/M/L, fence provided)
  and one of each size for houses (S/M/L), with size-tiered pricing.
- **FR-009 (Extensibility)**: All core content (cultures, trades, structures, dungeons, contracts) SHOULD be
  data-driven with schemas for validation.
- **FR-010 (Cross-Edition)**: Core gameplay MUST function on Java servers with Bedrock-bridge clients; provide
  parity fallbacks where features cannot be represented.
- **FR-011 (Determinism)**: Authoritative logic MUST run server-side with tick-aligned deterministic updates; no
  client authority for economy/reputation/loot.
- **FR-012 (Performance)**: Under the "Medium" profile (100 players, 50 villages, 500 villagers, 200 mobs), the mod
  MUST not reduce TPS below 20; perf targets defined in constitution apply.
- **FR-013 (Security/Anti-Exploit)**: Validate all client inputs; rate-limit sensitive ops; include dupe/exploit
  mitigations for economy and loot.
- **FR-014 (Save/Migration)**: World-save formats MUST be versioned with forward-only migrations and backups.
- **FR-015 (Localization)**: All user-facing text MUST be localizable.

*NEEDS CLARIFICATION markers kept ≤ 3 as required.*

### Key Entities *(include if feature involves data)*

- **Culture**: Id, name, localization, structure set, profession set, style rules.
- **Village**: Id, cultureId, wealth, population, reputationMap(player→score), projects[], relations[]
- **Project**: Id, buildingRef, cost, progress, contributors[], status, unlock effects.
- **TradeOffer**: Id, inputs, outputs, reputationReq, culture tags, dynamic pricing flags.
- **CurrencyWallet**: owner (player/village), balance, transaction log (server-authoritative). Denominations:
  100 Millz = 1 Billz; 100 Billz = 1 Trills. Base currency: Dollaz. Storage: int64 balance in Millz (smallest unit),
  to ensure lossless math and deterministic replication. Methods: credit(millz), debit(millz), formatDollaz(),
  parse(input), getBreakdown() -> {trills, billz, millz}.
- **Contract**: Id, type(fetch/defense/dungeon), objectives, rewards, timers, issuerVillageId, participants.
- **DungeonInstance**: seed, layoutRef, difficultyScale, state, rewardsIssued[].
- **EnemyFaction**: id, traits, spawn rules, loot tables.
- **RelationshipEdge**: villageA, villageB, status, score, lastChangedAt, modifiers.
- **Property**: id, villageId, type(plot|house), size(S|M|L), lot bounds, price, furnishings, owner, permissions.
- **PlayerProfile**: playerId, reputationMap, ownedProperties[], lastContributionAt.

## Success Criteria *(mandatory)*

<!--
  ACTION REQUIRED: Define measurable success criteria.
  These must be technology-agnostic and measurable.
-->

### Measurable Outcomes

- **SC-001 (Cross-Play)**: Java and Bedrock-bridge clients can trade, view project progress, and complete a contract
  together with no desyncs observed in 3 consecutive runs.
- **SC-002 (Progression)**: A new player can reach the first village upgrade milestone within 30–60 minutes of active
  play (median), with at least two distinct contract types available.
- **SC-003 (Performance)**: On the Medium profile world, p95 tick time ≤ 8 ms and p99 ≤ 12 ms with this mod enabled.
- **SC-004 (Retention Proxy)**: ≥80% of test players report “clear village growth feedback” and complete at least one
  contract within the first session (30 minutes).
- **SC-005 (Stability)**: Save/load cycles across 3 server restarts retain village states, ownership, and relations
  without data loss.

## Constitution Gates Checklist *(fill before implementation)*

Confirm the feature satisfies or has a plan for:

- Cross-Edition Compatibility: Target Java server with standard Bedrock bridge; parity fallbacks defined in design.
- Deterministic Multiplayer Sync: All economy/reputation/dungeon logic server-authoritative; add deterministic tests.
- Performance Budgets: Medium profile targets from constitution; perf test harness defined in plan.
- Modularity & Public APIs: Modules: cultures, economy, reputation, dungeons, relations, property; API changes listed
  in plan.
- Save/Migration: Versioned schemas for village/project/reputation/contract/dungeon/property; forward-only migrations.
- Observability: Structured logs with identifiers and counters for per-system tick time; debug flags documented.
- Cultural/Balance review: Cultural authenticity review checklist required for each culture set; economy audited for
  sources/sinks.
- Security/Anti-Exploit: Input validation, rate limits, anti-dupe tests for currency/loot.

## Clarifications

### Session 2025-11-04

- Q: Currency naming and denominations? → A: Dollaz as the currency, with denominations Millz, Billz, Trills;
  100 Millz = 1 Billz; 100 Billz = 1 Trills. Denominations are interchangeable and auto-condense in the player's
  inventory/UI (and auto-make change) with no glossary required.
- Q: Initial launch cultures? → A: Roman, Viking, Middle Ages, Native American, British Colonial, Egyptian, Chinese.
- Q: Property ownership limits per player? → A: One of each size for plots (S/M/L) and one of each size for houses (S/M/L).

## Assumptions & Open Questions

None at this time (see Clarifications for resolved items).

## Appendix: Currency Mechanics (Dollaz)

- Base currency: Dollaz (server-authoritative wallet, not item stacks). Denominations: Millz (base unit), Billz,
  Trills; 100 Millz = 1 Billz; 100 Billz = 1 Trills (i.e., 10,000 Millz = 1 Trills).
- Storage and arithmetic: int64 counts in Millz only; all operations are integer-safe, deterministic, and lossless.
- Display: auto-condense to the largest denominations; auto-make change on spend. Example: 12,345 Millz →
  1 Trills, 234 Billz, 45 Millz.
- Determinism test: depositing 100,000 times 1 Millz MUST equal withdrawing exactly 1,000 Billz with zero remainder.
- Bounds: prevent negative balances; guard against overflow (cap at max int64 Millz and reject transactions that would
  exceed it).
