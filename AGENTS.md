# Agent Guidelines for Coding Tasks in spec-billineire

Last updated: 2026-01-16

## Overview

This document codifies how agents should operate in this repository: building, linting, testing, coding style, and error handling.

It complements existing Copilot instructions at `.github/copilot-instructions.md` and any cursor rules if present.

Always prefer explicit, deterministic commands and avoid destructive actions unless requested.

## Active Technologies

- Java 17 (Paper 1.20+); optional Kotlin 1.9 (JVM 17) + Paper API; Adventure API (signage/messages); Jackson/Gson for JSON; FAWE (001-village-building-ux)
- World-save for placed blocks; plugin JSON for cultures/structure sets; persistent (001-village-building-ux)
- Java 17 (Paper 1.20+), optional Kotlin 1.9 (JVM 17) + Paper API (or Purpur fork), Geyser + Floodgate, Vault API, LuckPerms API, WorldGuard + FAWE (optional), MythicMobs (optional), Adventure API, Jackson/Gson for JSON (001-village-overhaul)

## Project Structure

```
src/
tests/
```

## Cursor and Copilot Rules

- Cursor rules: none detected in `.cursor/rules/` or `.cursorrules`.
- Copilot rules: follow guidance in `.github/copilot-instructions.md`. Do not omit essential security or reliability constraints; avoid leaking secrets; prefer explicit code comments where necessary.

## Build, Lint, and Test Commands (Gradle)

### Build
```
./gradlew clean build
```
Ensure `JAVA_HOME` points to a Java 17 JDK. Cache dependencies where possible in CI.

### Lint / Static Analysis
```
./gradlew check
```
Includes checkstyle, pmd, spotless if configured. Run individual checks to focus on a specific issue set if needed.

### Unit Tests
- Run all tests:
  ```
  ./gradlew test
  ```
- Run a single test class:
  ```
  ./gradlew test --tests "com.example.MyTest"
  ```
- Run a single test method:
  ```
  ./gradlew test --tests "com.example.MyTest.testMethod"
  ```
- Run integration tests:
  ```
  ./gradlew test --tests "*IntegrationTest"
  ```

### Test Coverage
```
./gradlew test jacocoTestReport
```

## Code Style Guidelines

### Language and Tooling
- Java 17 baseline; Kotlin 1.9 optional if used.
- Gradle standard project layouts.

### General Principles
- Be explicit, readable, and maintainable.
- Small, well-scoped commits with clear messages.

### Imports
- Group imports in one block; avoid wildcard imports.
- Order: static imports first, then other imports; within each group, sort alphabetically.

### Formatting
- 4 spaces per indentation level.
- Braces on the same line for methods and control structures.
- Maximum line length: 100-120 characters; wrap gracefully.
- Use blank lines to separate logical sections within a file.

### Types and Generics
- Prefer specific types; avoid raw types.
- Favor `Optional` where a value may be absent; avoid nulls where possible.
- Use bounded wildcards judiciously (e.g., `List<? extends Foo>` when appropriate).

### Naming Conventions
- Packages: lowercase (`com.example.pkg`).
- Classes and interfaces: PascalCase (`MyService`, `DataRepository`).
- Methods and fields: camelCase, descriptive but concise.
- Constants: `ALL_CAPS` with underscores (`MAX_RETRIES`).
- Test classes: end with `Test` (e.g., `UserServiceTest`).

### Error Handling
- Do not swallow exceptions; catch only what you can handle.
- Prefer specific exceptions; avoid catching generic `Exception` or `Throwable`.
- Use try-with-resources for IO and streams.
- Propagate meaningful context with exceptions (wrap where appropriate).

### Null-Safety and Contracts
- Prefer non-null by design; document nullability with annotations (`@Nullable`, `@NotNull`) if present.
- Validate inputs at boundaries; fail-fast with clear messages.

### Logging
- Use slf4j with parameterized messages: `log.debug("foo={}", foo);`
- Do not log sensitive data; respect privacy and security guidelines.

### Testing and Documentation
- Public API methods should have Javadoc with `@param`, `@return`, `@throws` when appropriate.
- Tests should be named after the behavior they verify; use descriptive assertions.

### Architecture and Modularity
- Favor clear module boundaries; avoid cross-cutting state.
- Use interfaces for pluggability and testability; inject dependencies where possible.

### Accessibility and i18n
- Strings should be externalized where appropriate; plan for localization.

### Versioning and Commits
- Use conventional style: `chore`, `feat`, `fix`, `refactor`, `test`, `docs`.
- Include short but meaningful messages; include task IDs when available.

## Working with Tasks

Before beginning a task, consider using any/all skills available in `.github/skills/`. Review relevant skill files for best practices, code patterns, and critical rules related to the task at hand.

Tool routing and MCP priorities:
- Prefer Serena first for non-trivial codebase navigation and editing when it is available. Ideal use cases: symbol lookup, reference tracing, rename/refactor, cross-file API discovery, targeted body replacement, and large Java/Kotlin code comprehension. Only skip Serena for tiny 1-2 file tasks, exact-string hunts, or plain docs/shell edits where file-level tools are faster.
- Prefer running build, test, and playtest scripts directly in the terminal (e.g., `./gradlew clean build`, `scripts/ci/sim/*`) instead of relying on MCP tool calls.
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

### Scripting & CI Portability
CI/test scripts target Windows PowerShell 5.1 as a baseline. Use ASCII-only output (replace ✓/✗/⚠ with OK/X/!), single-quoted regex with explicit `[0-9]` classes (avoid `\d` in double-quoted strings), escape `[]` and `()` when needed, and favor simple readiness checks (substring 'Done'). Validate with `Get-Command -Syntax` in CI to fail fast on parser errors.

### Testing and QA
- When completing a task, if a playtest makes sense, suggest it to the user along with a guide on how to test the most recent changes.
- When running tests and targeting specific behaviors (like village generation), if logs indicate the event happens well before the end of the test (and we are effectively waiting for nothing): run the test with fewer ticks the next time.
- 1000 ticks is more than enough time for village generation and structure placement tests.

### Documentation
- Do not leave TODO comments in code snippets. Instead create a complete User Story/Task(s) for any unfinished work.
- `./specs/` and `./specify/memory/` are living documents. When requirements change, update these files accordingly. Start with the constitution, then work your way down the spec-kit hierarchy to specification files, the plan files, and finally the tasks files.
- If a task is too big to address in a single pass and/or larger architectural changes are needed: update `plan.md` & `tasks.md` to systematically address the issue bit by bit.
- Include task IDs in commit messages when applicable.
- When summarizing changes made, include instructions for how to QA the changes effectively.

### Decision-Making
- If you encounter an ambiguous requirement, ask for clarification before proceeding.
- If you do not know something, admit it and suggest ways to find the answer.
- If the task seems unnecessary or redundant, explain why and suggest alternatives.
- Never make assumptions about user intent; always seek explicit confirmation.
- Never lie or fabricate information; provide only verified facts.
- After each task, review the user's playtest results and adjust `tasks.md` to ensure we focus on unresolved issues before moving on to new features/tasks. De-duplicate, re-prioritize, remove, add or modify any task in `tasks.md` as needed based on playtest feedback.

## Code Examples

Import order:
```java
import static java.util.Objects.requireNonNull;
import java.util.List;
import java.util.Map;
```

Try-with-resources:
```java
try (BufferedReader br = new BufferedReader(new FileReader(path))) {
  return br.readLine();
} catch (IOException e) {
  throw new UncheckedIOException(e);
}
```

Logging:
```java
private static final Logger log = LoggerFactory.getLogger(MyClass.class);
log.debug("User {} logged in at {}", userId, Instant.now());
```

## Maintaining this Document

- Add notes about new linters, test targets, or language versions as the project evolves.
- Keep AGENTS.md and Copilot guidelines up to date.
- Verify that AGENTS.md is included in PRs where agent onboarding is touched.
