# Quickstart — Village Overhaul (Structures First)

This quickstart explains how to validate structure generation, paths, and main-building designation
in a headless server harness.

## Prerequisites
- Java 17 installed and on PATH
- Gradle wrapper available (plugin/)
- Paper server harness scripts under scripts/ci/sim/

## Build the plugin
- Use the Gradle wrapper in plugin/ to build the JAR.

## Run headless Paper harness
- Use scripts/ci/sim/run-headless-paper.ps1 to launch a test server and RCON module.

## Test flow (admin/test commands)
1. Create a test village at a target location.
2. Generate structures for the culture’s structure set with grounded placement.
3. Generate inter-building paths and verify connectivity.
4. Designate the main building and persist.
5. Refresh signage and trigger onboarding greeter to sanity-check UX.

## Artifacts
- Logs include [STRUCT] markers for placement, re-seat/abort, and path metrics.
- Reports (if enabled) summarize path connectivity and main-building presence.

## Notes
- Minor, localized terraforming is allowed for natural placement.
- All operations are deterministic from seeds and state.
