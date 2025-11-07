# Quickstart — Village Overhaul (Structures First)

This quickstart explains how to validate structure generation, paths, main-building designation,
and asynchronous construction behavior in a headless server harness.

## Prerequisites
- Java 17 installed and on PATH
- Gradle wrapper available (plugin/)
- Paper server harness scripts under scripts/ci/sim/

## Build the plugin
- Use the Gradle wrapper in plugin/ to build the JAR.
- Ensure WorldEdit is available (FAWE preferred when installed) for structure operations.

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
