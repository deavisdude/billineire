---
name: agentic_toolchain
description: "Use when setting up or repairing AI agent productivity in this workspace, or when choosing between Serena, Context7, GitHub, and shell fallbacks for the current task."
---

# Agentic Toolchain

Use this skill for workspace-level agent enablement, not for normal feature work.

## Goals

- Keep the Windows CLI toolchain usable for both humans and agents: `rg`, `fd`, `jq`, `delta`, `aider`, `gemini`, `mcp-inspector`.
- Treat `.vscode/mcp.json` as the shared source of truth for workspace MCP servers.
- Prefer no-secret servers first: `github`, `context7`, and `serena`.

## Bootstrap

- Run `scripts/agent/install-agent-toolchain.ps1` to install or refresh the local toolchain.
- After changing `.vscode/mcp.json`, use `MCP: List Servers` or restart chat so VS Code re-discovers tools.
- Use `inputs` or `envFile` in `mcp.json` for secrets. Do not hardcode tokens.

## Workspace-Specific Priorities

- Use terminal shell commands for build and playtest workflows (e.g., `./gradlew clean build`, `scripts/ci/sim/*`).
- Use `context7` for current API and library docs.
- Use `github` for repository metadata, issues, pull requests, and search.
- Use `serena` when symbol-level navigation/editing is better than file-level grep.

## Tool Routing

- Use `serena` first for non-trivial code navigation or editing across Java, Kotlin, JSON, YAML, Markdown, or mixed multi-file tasks.
- Use terminal scripts (e.g., `./gradlew clean build`, `scripts/ci/sim/*`) for build, deploy, test, and playtest tasks.
- Use `context7` first for version-sensitive upstream docs or examples.
- Use `github` first for issue, pull request, branch, release, and repository search work.
- Use shell fallbacks only when the preferred MCP or semantic tool is unavailable or clearly overkill.

## Validation

- Verify commands with `Get-Command rg, fd, jq, delta, aider, gemini, mcp-inspector`.
- Validate `.vscode/mcp.json` and `.vscode/settings.json` for JSON errors.
- If an MCP server fails, inspect its output with `MCP: List Servers` -> `Show Output`.