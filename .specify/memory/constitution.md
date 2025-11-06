<!--
Sync Impact Report
- Version change: 1.1.0 → 1.2.0
- Modified sections:
  - Core Principles (added new principle: "Village Building & Player Onboarding")
  - Development Workflow, Review Process, and Quality Gates (added village-building gate)
- Added sections: "Village Building & Player Onboarding" (Principle X)
- Removed sections: None
- Templates requiring updates:
	- ✅ .specify/templates/plan-template.md (add village-building compliance gate when applicable)
	- ✅ .specify/templates/spec-template.md (add checklist item for village-building & UX when applicable)
	- ✅ .specify/templates/tasks-template.md (add constitution-driven task type for village building & UX)
	- ⚠ README.md/docs/* (optional: reference new village-building rules where relevant)
- Follow-up TODOs: Consider lightweight developer guide in docs/ covering main-building designation rules and signage examples
-->

# Spec Billineire Constitution

## Core Principles

### I. Cross-Edition Compatibility & Mod Interoperability (NON-NEGOTIABLE)
The mod MUST run on the latest stable Minecraft release and remain compatible with
multiplayer servers that bridge Java and Bedrock editions (e.g., via proxy/translation layers).
Features MUST not depend on client-only capabilities unavailable to Bedrock clients, and
fallbacks MUST exist where parity is not possible. Interoperability with other mods is required:
avoid invasive mixins/hooks when a stable extension point exists, and prefer capability/registry
based integrations. Maintain a public compatibility matrix and CI smoke tests for:
- Java-only server
- Java server with Java+Bedrock bridge
- Java server with common companion mods enabled

Rationale: Cross-play servers are a core target; breaking parity or common mod integrations
undermines adoption.

### II. Deterministic Multiplayer Synchronization (NON-NEGOTIABLE)
All authoritative game logic runs server-side and MUST be deterministic. Use tick-aligned
updates, seed RNG with world/feature seeds, and ensure idempotent packet handling. No client-side
authority for economy, reputation, or dungeon state. Network payloads MUST be versioned and
schema-validated. Desync detection and a minimal replay/snapshot mechanism MUST exist for QA.

Rationale: Determinism ensures fair, reproducible outcomes and stable cross-play synchronization.

### III. Performance & Scalability Budgets
Sustain 20 TPS under the default “Medium” profile: 100 concurrent players, 50 loaded villages,
500 active villagers, and 200 pathing mobs. The mod’s aggregate server tick cost MUST be:
- p95 ≤ 8 ms; p99 ≤ 12 ms
- Per-village average tick cost ≤ 2 ms (amortized)
Memory overhead targets: ≤ 512 MB additional RSS at the Medium profile; ≤ 5 MB average per
loaded village. Provide configuration to scale AI/pathfinding, simulation radius, and update
frequencies. Heavy operations MUST be chunk/tile-gated, batched, and/or off-thread where safe;
never block the main tick beyond budget.

Rationale: Large multiplayer environments demand strict latency and memory controls.

### IV. Modularity & Maintainability
Organize features into modules with clear boundaries: cultures, economy, reputation, dungeons,
worldgen, AI, UI, networking, data. Public APIs MUST be documented and semantically versioned.
No cyclic dependencies; enforce dependency inversion at module edges. Data and logic separation is
required to enable content-only updates. Deprecations MUST include warnings for ≥1 minor release
before removal and an upgrade path.

Rationale: Modular design enables parallel development, easier testing, and safe extensibility.

### V. Gameplay Balance & Economy Integrity
Design balanced progression across cultures and professions. Economy systems MUST have audited
sources and sinks to prevent inflation/duplication exploits. Reputation changes MUST be earned via
in-game actions and be server-validated. Dungeons scale difficulty and rewards by player/team
progression and world state.

Rationale: Balanced mechanics sustain long-term servers and fair multiplayer.

### VI. Extensibility & Data-Driven Content
Prefer data-driven content (datapacks/JSON/registries/tags/loot tables) over code where possible.
All cultural sets, structures, trades, professions, and dungeon layouts SHOULD be definable via
data, with validation schemas. Provide stable extension points and minimal plugin interfaces so
third parties can add content without forking.

Rationale: Data-first design accelerates content updates and community contributions.

### VII. Observability, Testing, and QA Discipline
Provide structured logging (with correlation IDs), debug toggles, and performance counters for
per-system tick time. Automated tests MUST include: unit tests for core logic, contract tests for
network payloads, cross-play integration tests, and performance regression tests against the Medium
profile. Ship a headless simulation harness to advance ticks and assert invariants.

Rationale: Visibility and rigorous testing prevent regressions in complex simulations.

### VIII. Save Compatibility & Migration Safety
World-save schemas MUST be versioned. Any breaking change requires a forward-only migration with a
backup step and verification. Never silently drop data. Document downgrade limitations explicitly.

Rationale: Servers depend on safe upgrades over long-lived worlds.

### IX. Security & Anti-Exploit Posture
Validate all client inputs. Never trust client-side reputation, currency, or loot claims.
Rate-limit sensitive interactions. Add server-configurable permissions/ops gates for admin-facing
tools. Document known exploit mitigations and test for dupe paths in CI where feasible.

Rationale: Multiplayer at scale attracts exploits; prevention protects the economy and reputation.

### X. Village Building & Player Onboarding
Village construction and player-facing UX MUST follow deterministic, grounded placement rules and
provide clear wayfinding:

- Grounded Placement: Spawn/expand village buildings only on solid, loadable terrain with
	collision-safe clearance. No floating/embedded structures. Validate foundation blocks and ensure
	interior air-space before placement; abort or re-seat if invalid.
- Inter-Building Paths: Generate simple, traversable paths connecting key village buildings. Paths
	MUST avoid placing blocks that break player or mob movement and SHOULD prefer existing terrain.
- Main Building: Each culture MUST designate a single "main building" type used for first-contact
	UX. Persist this designation in data and ensure only one is active per village.
- Signage for Projects: The main building MUST surface current village projects and material
	requirements via in-world signage or UI-equivalent, server-side authoritative and localized via
	the language/Adventure API.
- Greeter Villager: On player entry into the main building area, a designated greeter villager (or
	equivalent server-driven prompt) MUST introduce the village, surface active projects, and provide
	a clear next action. Triggering MUST be server-side and rate-limited; no client-only logic.
- Performance/Determinism: All placement, pathing, and triggers MUST be chunk-gated and
	deterministic from world seed/state. Heavy calculations MUST be budgeted under Performance
	Principles and never block the main thread beyond budget.

Rationale: Grounded placement, simple paths, and an explicit main-building greeter create a
coherent onboarding experience without sacrificing determinism or performance on large servers.

## Engineering Standards & Constraints

- Coding standards: consistent naming, null-safety, and immutable data where practical for shared
	state; avoid static global state for gameplay logic.
- Threading: Only thread-safe subsystems may offload work; use job queues and return results to the
	main thread via scheduled tasks; never mutate world state off-thread.
- Pathfinding/AI: Use spatial partitioning and cached nav data; cap evaluations per tick; budgeted
	re-plans; avoid O(N²) where N is entities.
- Networking: Compact, versioned packets; avoid excessive NBT in hot paths; compress when payloads
	exceed thresholds; never send data Bedrock clients cannot represent—provide fallbacks.
- Worldgen/Dungeons: Gate expensive generation; pre-bake structures where possible; ensure
	reproducibility from seed.
- Compatibility: Keep mixins/hooks minimal and feature-scoped; prefer official APIs/extension
	points; feature flags to soft-disable incompatible modules.
- Documentation: Public APIs and data schemas MUST be documented and versioned.

### Scripting & CI Portability (Windows PowerShell 5.1 baseline)

All repository scripts and CI helpers MUST be robust across common developer
environments, with Windows PowerShell 5.1 as the minimum baseline for Windows.

- Prefer ASCII-only log/output in CI. Avoid Unicode glyphs such as checkmark/cross
	(e.g., ✓, ✗, ⚠). Use ASCII equivalents: OK, X, and !.
- PowerShell regex patterns MUST be single-quoted to avoid unintended escapes
	(e.g., use 'npc\.tick_time_ms[:\s]+([0-9]+\.?[0-9]*)').
- Do not rely on "\d" inside double-quoted strings; prefer explicit classes like
	[0-9]. Escape square brackets and parentheses as needed when not single-quoted.
- Keep startup log parsing resilient: prefer a simple 'Done' substring match over
	highly specific timestamped patterns.
- Validate scripts with Get-Command -Syntax where possible and ensure they fail
	fast with clear, ASCII-only error messages.

## Development Workflow, Review Process, and Quality Gates

Every feature/PR MUST pass the following Constitution Check before merge:

- Cross-Edition Compatibility: Update the compatibility matrix entry; provide evidence (logs/screens)
	from Java-only and Java+Bedrock-bridge test servers.
- Deterministic Sync: Include/execute a deterministic simulation test; no client-authority paths.
- Performance Budgets: Provide tick-time measurements (p95/p99) under the Medium profile and memory
	deltas; include a rollback plan if budgets regress.
- Modularity: Document module boundaries and public API changes; no new cyclic dependencies.
- Save/Migration: Update schema version, migration script, and backup/verify steps when storage
	changes.
- Observability: Ensure logs/metrics for new systems include identifiers and are behind debug flags.
- Cultural/Balance: Attach cultural review notes and balance rationale for economy/dungeon changes.
- Security: Note input validation and permission gates; include anti-exploit checks if applicable.

- Scripting & CI Portability: Verify test/CI scripts are PowerShell 5.1 compatible
	on Windows, avoid Unicode glyphs in logs, use single-quoted regex patterns with
	explicit [0-9] character classes, and keep server readiness detection simple
	(e.g., substring 'Done').

Gate failures require a written justification in the plan’s Complexity Tracking section and an
explicit follow-up task with an owner and due date.

## Governance

This Constitution supersedes ad-hoc practices. Amendments follow an RFC process:

1. Open an “RFC: Constitution Amendment” issue describing the change, rationale, and impact.
2. Discuss for ≥72 hours; gather community/maintainer feedback.
3. Approval requires at least two maintainers, with no outstanding blocking concerns.
4. Versioning: Semantic versioning for this document
	 - MAJOR: Backward-incompatible removals/redefinitions of principles
	 - MINOR: New principle/section or material expansion
	 - PATCH: Clarifications/wording/typos with no semantic effect
5. Compliance: Reviews MUST verify Constitution Check gates. Exceptions require a temporary waiver
	 with an expiration and tracking issue.
6. Review cadence: Quarterly review of principles, budgets, and compatibility targets.

**Version**: 1.2.0 | **Ratified**: 2025-11-04 | **Last Amended**: 2025-11-05
