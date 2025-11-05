# Research & Decisions — Village Overhaul (Plan A)

Date: 2025-11-04
Branch: 001-village-overhaul
Spec: specs/001-village-overhaul/spec.md

## Paper vs Purpur vs Spigot
- Decision: Target Paper (latest stable for MC 1.20+). Purpur acceptable if features needed; avoid Spigot due to fewer performance APIs.
- Rationale: Paper exposes async-friendly and performance patches, broad ecosystem support.
- Alternatives: Spigot (fewer hooks), Purpur (more patches but adds variance across hosts).

## Bedrock Bridge: Geyser + Floodgate
- Decision: Support Bedrock via Geyser (protocol translation) and Floodgate (auth bridging). No client mods required.
- Rationale: Best-in-class cross-play path with large adoption.
- Alternatives: Custom proxies — higher maintenance; closed bridges — lock-in.

## Economy Bridge: Vault
- Decision: Provide a Vault Economy adapter over the server-authoritative Dollaz wallet.
- Rationale: Ecosystem compatibility; other plugins can read balances.
- Alternatives: Standalone economy only — reduces interoperability.

## Regions & Building Ops: WorldGuard + FAWE (optional)
- Decision: Integrate with WorldGuard for region ownership/protection; FAWE for safe edits.
- Rationale: Mature tools reduce risk when doing controlled building/upgrades.
- Alternatives: Custom region system — slower to harden; vanilla edits — risk of TPS spikes and griefing.

## Permissions: LuckPerms
- Decision: Use LuckPerms API for ACL and role-based permissions.
- Rationale: De facto standard; powerful and stable.
- Alternatives: Bukkit built-in perms only — too limited for complex ACLs.

## AI/Enemies: Plugin-native first, optional MythicMobs
- Decision: Implement deterministic behaviors in plugin; if MythicMobs present, map enemy archetypes for richer visuals/skills.
- Rationale: Keep server authority; tap into MythicMobs ecosystem without hard dependency.
- Alternatives: Full custom AI only — more effort; full dependency — reduces portability.

## Data Format & Storage
- Decision: JSON/YAML for data under plugin data folder; versioned schemas; forward-only migrations.
- Rationale: Human-editable, Git-diffable, easy to validate.
- Alternatives: SQLite/DB for all state — overhead for initial scope; reserve for metrics later.

## Testing Harness
- Decision: JUnit 5 + MockBukkit for unit/integration; deterministic tick simulator; state-hash assertions.
- Rationale: Fast CI; reproducible tests.
- Alternatives: Minestom testbed — good option later for headless world sim.

## Performance & Observability
- Decision: Spark/Timings for p95/p99; structured logging with correlation IDs; counters per subsystem; optional Prometheus exporter.
- Rationale: Meet constitution gates; actionable diagnostics.
- Alternatives: Ad-hoc logs — insufficient for regressions.

## Dungeons & Structures
- Decision: Pre-baked structure templates + deterministic placement; budgeted generation; re-roll on conflicts.
- Rationale: Predictable and safe under TPS budgets.
- Alternatives: Heavy runtime worldgen — risk TPS spikes.

## Security Posture
- Decision: Server-authoritative integer economy (Millz unit); transaction logs; rate limits; server-side validation for contracts and loot.
- Rationale: Prevent dupes/exploits.
- Alternatives: Client-mixed authority — not allowed by constitution.
