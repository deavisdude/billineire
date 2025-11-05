# Implementation Plan: Village Overhaul (Plan A: Plugin-first)

**Branch**: `001-village-overhaul` | **Date**: 2025-11-04 | **Spec**: `specs/001-village-overhaul/spec.md`
**Input**: Feature specification from `/specs/001-village-overhaul/spec.md`

## Summary

Deliver a cross-edition village overhaul as a Paper/Purpur plugin with a deterministic tick-driven core. Content is data-driven (JSON/datapacks) and compatible with Bedrock via Geyser/Floodgate. Subsystems include a server-authoritative Dollaz economy (Millz/Billz/Trills denominations), trade-funded village projects, reputation and contracts, deterministic dungeons/enemies, inter-village relations, and property ownership limits (one of each size S/M/L for plots and houses). Optional integrations: Vault (economy bridge), LuckPerms (ACLs), WorldGuard/FAWE (regions/build), MythicMobs (AI), Spark/Timings (profiling).

## Technical Context

**Language/Version**: Java 17 (Paper 1.20+), optional Kotlin 1.9 (JVM 17)
**Primary Dependencies**: Paper API (or Purpur fork), Geyser + Floodgate, Vault API, LuckPerms API, WorldGuard + FAWE (optional), MythicMobs (optional), Adventure API, Jackson/Gson for JSON
**Storage**: Versioned JSON/YAML under plugin data folder for world state; rolling backups. Future-proofed with migration scripts. Optional SQLite for metrics if needed.
**Testing**: JUnit 5 + MockBukkit for unit/integration; headless deterministic tick harness; Spark/Timings snapshots; contract tests around state hashes.
**Target Platform**: Java server (Paper/Purpur) with optional Bedrock bridge (Geyser/Floodgate)
**Project Type**: Single plugin project (Gradle) with clear packages per subsystem
**Performance Goals**: Maintain 20 TPS; p95 ≤ 8 ms, p99 ≤ 12 ms at Medium profile; ≤ 2 ms amortized per village
**Constraints**: No client mods; all authority server-side; integer-only economy; parity fallbacks for Bedrock
**Scale/Scope**: Medium profile: 100 players, 50 villages, 500 villagers, 200 pathing mobs

### Custom Villagers

Components:
- CustomVillagerService: lifecycle (spawn/despawn/persist), culture/profession binding, village proximity caps.
- VillagerAppearanceAdapter: culture-driven attire via vanilla-compatible visuals (armor, colors, nametags, teams); optional resource-pack models on Java; Bedrock-safe fallbacks.
- VillagerInteractionController: intercepts PlayerInteract events, cancels vanilla trading on Custom Villagers, renders dialogue/actions using chat/actionbar and inventory GUI fallback; server-side validation and rate limits.
- BedrockParityStrategy: no client mods; identical interaction flow; visual degradation is graceful and non-desyncing.

Data:
- Schema `schemas/custom-villager.json`: cultureId, professionId, appearanceProfile, dialogueKeys, spawnRules.

Integration:
- Hooks into CultureService for professions/attire; ProjectService and WalletService for trade→project flows; optional WorldGuard/FAWE to keep spawns within village regions.

Performance budgets:
- Cap Custom Villagers per village (configurable, default ≤ 10).
- Target ≤ 0.05 ms average tick per Custom Villager; remain within ≤ 2 ms per-village amortized.
- Throttle AI/interaction updates by distance and activity; never mutate world off-thread.

Compatibility & Parity:
- Add compatibility-matrix entries for Java-only and Java+Bedrock (Geyser/Floodgate) verifying interaction parity and acceptable visual fallbacks for Custom Villagers.

## Constitution Check

Pre-design gate rationale (Plan A):

- Cross-Edition Compatibility: Runs on Paper with Geyser/Floodgate; UI parity fallbacks defined; tracked in `docs/compatibility-matrix.md` and CI smoke.
- Deterministic Multiplayer Sync: Single authoritative tick loop; deterministic RNG seeding; idempotent events; no client-authority for economy/reputation/loot.
- Performance Budgets: Per-system tick budgets; Spark/Timings profiling; CI perf harness executes N ticks and reports p95/p99; configurable throttles.
- Modularity: Packages/modules: cultures, economy, projects, reputation, contracts, dungeons, relations, property, compat. Public plugin services documented.
- Save/Migration: Versioned schemas; forward-only migrations; backup/verify pre-commit to new version; never drop unknown fields.
- Observability: Structured logs, correlation IDs, metrics counters (tick time per subsystem, queue lengths); debug flags; state hash snapshots for tests.
- Cultural/Balance: Culture review checklist per culture; economy sink/source ledger; difficulty scaling guidelines.
- Security/Anti-Exploit: Input validation; rate limits; permission gates; anti-dupe in wallet/loot; region protections for builds.

- Custom Villagers — Observability: Add NPC metrics (npc.ticks, npc.tick_time_ms, npc.interactions_per_sec, npc.interaction_denied_rate, npc.open_sessions) and debug toggles for appearance profiles and interaction flow state.
- Custom Villagers — Security: Enforce interaction rate limits (e.g., ≤2 opens/sec per player), server-side validation of selections, and deny if NPC moved/despawned mid-flow.

Re-check after Phase 1 design: PASS (no violations identified).

## Project Structure

### Documentation (this feature)

```text
specs/001-village-overhaul/
├── plan.md              # This file
├── research.md          # Decisions & rationale
├── data-model.md        # Entities, relationships, validation
├── quickstart.md        # Run guide for Paper + Geyser/Floodgate
└── contracts/
    └── openapi.yaml     # Admin/diagnostic API for tests/ops
```

### Source Code (repository root)

```text
plugin/
├── src/main/java/
│   └── com/example/villageoverhaul/
│       ├── VillageOverhaulPlugin.java
│       ├── economy/
│       ├── projects/
│       ├── reputation/
│       ├── contracts/
│       ├── dungeons/
│       ├── relations/
│       ├── property/
│       └── compat/ (vault, geyser, wg/fawe, mythicmobs)
├── src/main/resources/
│   ├── plugin.yml
│   └── datapacks/
└── build.gradle

tests/
├── unit/
├── integration/
└── perf/
```

**Structure Decision**: Single Gradle plugin project for fastest path. Subsystems separated by packages; compat adapters isolated. Tests grouped by unit/integration/perf. Datapacks and resource pack assets under resources/.

## Complexity Tracking

No Constitution violations at this stage.
