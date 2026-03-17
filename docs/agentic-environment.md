# Agentic Environment

This workspace already had strong game-specific automation before any AI-specific setup:

- Java 17/21 Paper plugin build with Gradle Shadow JAR output.
- JUnit 5 plus MockBukkit tests for fast and integration suites.
- A headless Paper server harness driven by PowerShell and RCON.
- WorldEdit/FAWE-aware placement flows and detailed structure/path diagnostics.

The gaps were operational rather than architectural: missing shell ergonomics, no shared MCP configuration, and no repo-local MCP server that exposes the existing build and playtest harness directly to agents.

## What Was Added

- Shared workspace MCP configuration in `.vscode/mcp.json`.
- Shared workspace MCP autostart settings in `.vscode/settings.json`.
- Shell scripts for build and playtest automation in `scripts/ci/sim/` and `plugin/gradlew.bat`.
- A reproducible Windows bootstrap script at `scripts/agent/install-agent-toolchain.ps1`.
- Two workspace skills documenting the new workflows.

## Configured MCP Servers

- `github`: remote GitHub MCP server for repository operations.
- `context7`: up-to-date documentation retrieval via `@upstash/context7-mcp`.
- `serena`: semantic code navigation MCP server launched from the locally installed `serena` executable.

## Installed CLI Tooling

The bootstrap script installs or refreshes these Windows-friendly tools:

- `ripgrep` for fast code search.
- `fd` for fast file discovery.
- `jq` for JSON inspection.
- `delta` for readable Git diffs.
- `aider` as an additional terminal coding agent.
- `gemini` as a second terminal coding agent with MCP support.
- `mcp-inspector` for local MCP server debugging.
- `serena` for local semantic code navigation and editing.

## Recommended Agent Loop

1. Use `context7` or `github` MCP when external documentation or repo metadata is needed.
2. Run `./gradlew clean build` (and other Gradle commands) directly in the terminal before gameplay validation.
3. For placement-heavy fixes, run `scripts/ci/sim/test-village-generation.ps1` (or its PowerShell equivalent).
4. For persistence or multi-system behavior, run `scripts/ci/sim/run-scenario.ps1`.
5. Use `test-server/logs/latest.log` and `state-snapshot.json` for triage and follow-up.

## Manual Steps

- Open `MCP: List Servers` in VS Code and confirm that `github`, `context7`, and `serena` are trusted and started.
- Reload the VS Code window or restart chat after changing `.vscode/mcp.json` or `.vscode/settings.json`.
- Verify local executables with `Get-Command rg, fd, jq, delta, aider, gemini, mcp-inspector, serena`.
- If a server does not appear or does not start, use `MCP: List Servers` -> `Show Output` to inspect its logs.
- If workspace MCP servers are blocked by policy, `chat.mcp.access` must allow them at the organization level.

## Notes

- `.vscode/` stays ignored by default, but `.vscode/mcp.json` and `.vscode/settings.json` are intentionally whitelisted so the shared agent environment can live in source control.
- `serena` is high value for larger refactors, but its per-language capabilities depend on available language-server support.
- Windows sandboxing for local stdio MCP servers is not currently available in VS Code, so trust prompts still matter.