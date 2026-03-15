---
name: headless_playtest
description: "Use when running or debugging automated Minecraft plugin QA in this workspace: specBillineirePlaytest MCP, Gradle build/test, plugin deployment, headless Paper scenarios, RCON commands, village generation, structure placement, pathing, or playtest log triage."
---

# Headless Playtest

Use this skill whenever a task needs evidence beyond static code review.

## Preferred Workflow

- Build first with `plugin\gradlew.bat clean build`.
- Prefer the local MCP server tools when available instead of raw PowerShell scripts:
  - `gradle_build`
  - `deploy_plugin_jar`
  - `run_fast_village_generation`
  - `run_headless_scenario`
  - `inspect_latest_playtest`
  - `send_rcon_command`

## Shell Fallbacks

- `scripts/ci/sim/build-and-test.ps1`
- `scripts/ci/sim/test-village-generation.ps1`
- `scripts/ci/sim/run-scenario.ps1`

## What To Watch

- `[STRUCT][RECEIPT]` means a structure actually placed.
- `[SITE-REJECT]` captures terrain and validation failures.
- `PATH-COVERAGE` and `[PATH]` lines indicate path generation quality.
- `ZERO-PLACEMENT` is the key root-cause signal when villages fail entirely.
- `state-snapshot.json` and `test-server/logs/latest.log` are the main artifacts for post-run inspection.

## Heuristics

- Use fast village generation when testing placement regressions or structure counts.
- Use the full scenario harness when tick progression, persistence, or economy behavior matters.
- Keep the feedback loop short: run the smallest scenario that can falsify the hypothesis.