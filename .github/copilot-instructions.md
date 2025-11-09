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
When completing tasks, stop and test your work, then return suggestions and a summary to the user. Do not continue to the next task until instructed.
Scripting & CI Portability: CI/test scripts target Windows PowerShell 5.1 as a
    baseline. Use ASCII-only output (replace ✓/✗/⚠ with OK/X/!), single-quoted
    regex with explicit [0-9] classes (avoid \d in double-quoted strings), escape
    [] and () when needed, and favor simple readiness checks (substring 'Done').
    Validate with `Get-Command -Syntax` in CI to fail fast on parser errors.

When completing a task, if a playtest makes sense, suggest it to the user along with a guide on how to test the most recent changes.

Do not leave TODO comments in code snippets. Instead create a complete User Story/Task(s) for any unfinished work.

./specs/ and ./specift/memory/ are living documents. When requirements change, update these files accordingly. Start with the constitution, then work your way down the spec-kit hierarchy to specification files, the plan files, and finally the tasks files.

When running tests and targeting specific behaviors (like village generation), if logs indicate the event happens well before the end of the test (and we are effectively waiting for nothing): run the test with fewer ticks the next time.
<!-- MANUAL ADDITIONS END -->
