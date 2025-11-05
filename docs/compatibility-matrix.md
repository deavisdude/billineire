# Compatibility Matrix

This matrix tracks smoke-test coverage for the three mandatory server profiles defined by the
constitution. Update this file whenever compatibility changes or a new Minecraft release ships.

| Profile | Description | Target Minecraft Version | Bedrock Bridge | Companion Mods | Expected Status | Smoke Test Entry |
|---------|-------------|--------------------------|----------------|----------------|-----------------|------------------|
| Java (Vanilla) | Dedicated Java server running only this mod. Baseline for performance and determinism checks. | Latest stable (current: 1.21.x) | N/A | None | ✅ Supported | `scripts/ci/smoke/java-vanilla.ps1` |
| Java + Bedrock Bridge | Java server behind Geyser/Floodgate (or equivalent) allowing Bedrock clients. Ensures cross-edition parity. | Latest stable (current: 1.21.x) | Required | None | ✅ Supported | `scripts/ci/smoke/java-bedrock.ps1` |
| Java + Bridge + Mod Stack | Java server with Bedrock bridge and curated companion mods (e.g., map, economy, automation). Validates interoperability. | Latest stable (current: 1.21.x) | Required | `config/mod-stack.yaml` | ✅ Supported | `scripts/ci/smoke/java-bridge-mods.ps1` |

## Maintenance Guidelines

- Update the “Target Minecraft Version” column immediately after each major/minor release.
- Ensure smoke test scripts referenced above exist and are executable in CI on Ubuntu runners
	(use PowerShell Core). Scripts MUST exit non-zero if compatibility fails.
- Document any temporary regressions with a ⚠️ status and link to the tracking issue.
- Add new rows if additional compatibility scenarios become mandatory (e.g., snapshot builds).

## Smoke Test Artifacts

- `scripts/ci/smoke/java-vanilla.ps1`: Spins up a headless Java server, installs the mod, runs a
	short deterministic simulation scenario, and exports TPS/log summaries.
- `scripts/ci/smoke/java-bedrock.ps1`: Provisions the Java server and Bedrock bridge, connects a
	scripted Bedrock client, and validates handshake + sample interactions.
- `scripts/ci/smoke/java-bridge-mods.ps1`: Repeats the bridge scenario with the curated mod stack
	enabled; ensures registry collisions and capability checks pass.

> Until the automation is fully implemented, the CI workflow surfaces TODO placeholders. Replace
> them with the scripts above as soon as the infrastructure lands.
