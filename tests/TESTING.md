Testing policy

- Unit tests (fast):
  - Tag with `@Tag("unit")` optionally, but not required.
  - Must be deterministic and not depend on MockBukkit or a live Minecraft server.
  - Run with `./gradlew :plugin:testUnit` (excludes tests tagged `integration`).

- Integration tests (MockBukkit / heavy):
  - Tag tests that need a MockBukkit server or long-running harness with `@Tag("integration")`.
  - Keep these tests limited to lifecycle, scheduler, or any test that requires the server runtime.
  - Run with `./gradlew :plugin:testIntegration`.

- CI configuration (recommended):
  - Pull requests: run `testUnit` (fast unit suite) to keep CI responsive — our CI workflow runs this automatically.
  - Main / nightly: run `testIntegration` to exercise MockBukkit and heavier integration suites — CI runs this on pushes to `main` and on a nightly schedule.

CI examples (GitHub Actions):
- PRs: `./gradlew :plugin:testUnit` (fast, excludes tests tagged `integration`)
- Main/nightly: `./gradlew :plugin:testIntegration` (runs tests tagged `integration`)

- Helpers and patterns:
  - Use `com.davisodom.villageoverhaul.test.FakeWorld` for most world-related unit tests.
  - Reserve MockBukkit for a small curated set of integration tests; mark them with `@Tag("integration")`.
  - When converting tests, prefer Mockito-backed lightweight mocks over MockBukkit if only simple world/block behaviors are needed.

- Additions:
  - New Gradle tasks `testUnit` and `testIntegration` were added to `plugin/build.gradle`.
  - Add new shared fake-world helper in `plugin/src/test/java/com/davisodom/villageoverhaul/test/FakeWorld.java`.

Rationale: separating fast unit tests from heavier integration tests reduces flaky CI and shortens feedback loops while preserving full integration verification on main/nightly pipelines.