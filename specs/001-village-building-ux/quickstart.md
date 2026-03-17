# Quickstart — Village Overhaul (Structures First)

This quickstart explains how to validate structure generation, paths, main-building designation,
and asynchronous construction behavior in a headless server harness.

## Prerequisites
- Java 21 installed and on PATH
- Gradle wrapper available (plugin/)
- Paper server harness scripts under scripts/ci/sim/

## Build the plugin
- Use the Gradle wrapper in plugin/ to build the JAR.
- Ensure WorldEdit is available (FAWE preferred when installed) for structure operations.

## Configure inter-village spacing
- In `plugin/src/main/resources/config.yml`, set `village.minVillageSpacing` (default: 200).
- This setting enforces a minimum border-to-border distance between villages (bidirectional).
- First/early villages will start near (but not at) spawn; subsequent villages will generate as
	close as possible to an existing village without violating this distance.

## Run headless Paper harness
- Use scripts/ci/sim/run-headless-paper.ps1 to launch a test server and RCON module.
- Logs are ASCII-only and readiness detection is via substring 'Done' to keep CI portable.

## Test flow (admin/test commands)
1. Create a test village at a target location.
2. Generate structures for the culture’s structure set with grounded placement.
3. Verify asynchronous placement: ensure large structure preparation runs off-thread and block
	mutations are committed in small, main-thread batches. Observe visible row/layer progress
	(scaffolding/markers optional).
4. Generate inter-building paths and verify connectivity.
5. Designate the main building and persist.
6. Refresh signage and trigger onboarding greeter to sanity-check UX.
7. Validate inter-village spacing:
	 - Attempt to generate a second village within `minVillageSpacing` of the first → expect a
		 rejected site with logs indicating inter-village spacing enforcement.
	 - Place a second village as close as possible beyond the configured spacing and confirm success.

## Artifacts
- Logs include [STRUCT] markers for placement, re-seat/abort, path metrics, and async placement
	batches (commit size, queue progress). Builder state transitions may be logged when enabled.
- Reports (if enabled) summarize path connectivity, main-building presence, and construction
	progress snapshots.

## Notes
- Minor, localized terraforming is allowed for natural placement.
- All operations are deterministic from seeds and state.
- Large structure placement on the main thread causes server lag. Use asynchronous operations with
	main-thread batched commits only (per Constitution XI).
 - Inter-village spacing and borders are defined in the Constitution (v1.5.0, Principle XII). Borders
 	expand with construction and are clipped near neighboring borders.
