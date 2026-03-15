# spec-billineire Development Guidelines

Auto-generated from all feature plans. Last updated: 2025-11-04

## Active Technologies
- Java 17 (Paper 1.20+); optional Kotlin 1.9 (JVM 17) + Paper API; Adventure API (signage/messages); Jackson/Gson for JSON; FAWE (001-village-building-ux)
- World-save for placed blocks; plugin JSON for cultures/structure sets; persistent (001-village-building-ux)

- Java 17 (Paper 1.20+), optional Kotlin 1.9 (JVM 17) + Paper API (or Purpur fork), Geyser + Floodgate, Vault API, LuckPerms API, WorldGuard + FAWE (optional), MythicMobs (optional), Adventure API, Jackson/Gson for JSON (001-village-overhaul)

## Project Structure

```text
src/
tests/
```

## Commands

# Add commands for Java 17 (Paper 1.20+), optional Kotlin 1.9 (JVM 17)

## Code Style

Java 17 (Paper 1.20+), optional Kotlin 1.9 (JVM 17): Follow standard conventions

## Recent Changes
- 001-village-building-ux: Added Java 17 (Paper 1.20+); optional Kotlin 1.9 (JVM 17) + Paper API; Adventure API (signage/messages); Jackson/Gson for JSON; FAWE

- 001-village-overhaul: Added Java 17 (Paper 1.20+), optional Kotlin 1.9 (JVM 17) + Paper API (or Purpur fork), Geyser + Floodgate, Vault API, LuckPerms API, WorldGuard + FAWE (optional), MythicMobs (optional), Adventure API, Jackson/Gson for JSON

<!-- MANUAL ADDITIONS START -->
Before beginning a task, consider using any/all of the skills avaialble to you in /.github/skills/. Review relevant skill files for best practices, code patterns, and critical rules related to the task at hand.

Tool routing and MCP priorities:
- Prefer Serena first for non-trivial codebase navigation and editing when it is available. Ideal use cases: symbol lookup, reference tracing, rename/refactor, cross-file API discovery, targeted body replacement, and large Java/Kotlin code comprehension. Only skip Serena for tiny 1-2 file tasks, exact-string hunts, or plain docs/shell edits where file-level tools are faster.
- Prefer the local `specBillineirePlaytest` MCP server for build, deploy, test, playtest, RCON, and latest-log inspection work instead of ad-hoc terminal commands. Ideal tools: `gradle_build`, `gradle_test`, `deploy_plugin_jar`, `run_fast_village_generation`, `run_headless_scenario`, `inspect_latest_playtest`, and `send_rcon_command`.
- Prefer `context7` for version-sensitive external library/framework documentation and current examples.
- Prefer `github` MCP for issues, pull requests, branches, commits, releases, and repository search.
- When MCP or Serena are unavailable, fall back explicitly to `rg`, `fd`, `jq`, `delta`, and the existing PowerShell harness scripts rather than slower or more manual alternatives.
- Do not ignore these tools when the task overlaps their ideal use case; use them proactively and only fall back when the preferred tool is unavailable or clearly lower-value for the task.

When completing a task:
1. Validate all acceptance criteria are fully met
2. Run all applicable automated/headless tests
3. Verify tests pass and coverage requirements are met
4. If validation complete and successful: continue to the next task automatically
5. If validation fails: fix issues and re-validate
6. If scope clarification needed (beyond spec/story files): report findings and ask user
7. If human playtest required (explicitly noted in task): report findings and ask user

Scripting & CI Portability: CI/test scripts target Windows PowerShell 5.1 as a
    baseline. Use ASCII-only output (replace ✓/✗/⚠ with OK/X/!), single-quoted
    regex with explicit [0-9] classes (avoid \d in double-quoted strings), escape
    [] and () when needed, and favor simple readiness checks (substring 'Done').
    Validate with `Get-Command -Syntax` in CI to fail fast on parser errors.

When completing a task, if a playtest makes sense, suggest it to the user along with a guide on how to test the most recent changes.

Do not leave TODO comments in code snippets. Instead create a complete User Story/Task(s) for any unfinished work.

./specs/ and ./specift/memory/ are living documents. When requirements change, update these files accordingly. Start with the constitution, then work your way down the spec-kit hierarchy to specification files, the plan files, and finally the tasks files.

When running tests and targeting specific behaviors (like village generation), if logs indicate the event happens well before the end of the test (and we are effectively waiting for nothing): run the test with fewer ticks the next time.

1000 ticks is more than enough time for village generation and structure placement tests.

If a task is too big to address in a single pass and or larger architectural changes are needed: update plan.md & tasks.md to systematically address the issue bit by bit.

Include task IDs in commit messages when applicable.

When summarizing changes made, include instructions for how to QA the changes effectively.

If you encounter an ambiguous requirement, ask for clarification before proceeding.

If you do not know something, admit it and suggest ways to find the answer.

If the task seems unnecessary or redundant, explain why and suggest alternatives.

Never make assumptions about user intent; always seek explicit confirmation.

Never lie or fabricate information; provide only verified facts.

After each task, review the user's playtest results and adjust tasks.md to ensure we focus on unresolved issues before moving on to new features/tasks. De-duplicate, re-prioritize, remove, add or modify any task in tasks.md as needed based on playtest feedback.

Before prompting the user for a playtest and summarizing changes, run ./gradlew clean build from C:\Users\davis\Documents\Workspace\spec-billineire\plugin
<!-- MANUAL ADDITIONS END -->
