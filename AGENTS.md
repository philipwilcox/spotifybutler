# Kotlin Spotify Butler

These instructions apply to the Kotlin service under `kt/`. It is an independent Gradle application and should remain
buildable without relying on the Node service.

## Planning

Plan docs should include api-level/struct-or-class+function level specifications to ensure implementation will be
properly guided, but not raw actual code. This should apply either to "real" plan mode or just files I ask you to
make.

This is not an enterprise-grade project. Be extremely hesitant to suggest anything (outside of security essentials)
that are MORE functionality or more robust than I request.

## General modifications

- DO NOT run lint and unit tests on every step and modification. I like to sequence smaller changes then just run those
  manually before commit.

## Git staging

- Always stage source code files, tests, and sanitized test fixtures created or modified for the requested work before
  handoff.
- Never stage planning documents, including `*plan.md`, `resume.md`, or `todo.md`.
- Never stage secrets, secret properties, raw Spotify captures, draft fixtures, access tokens, or other personal
  payloads. Keep those files ignored or untracked.

## Build and quality checks

- Use the Gradle wrapper from this directory: `./gradlew <task>`.
- The project currently uses Kotlin 2.4 and the Java/JVM 25 toolchain. Keep dependency and toolchain changes in
  `build.gradle.kts` deliberate and compatible with the wrapper.
- Please minimize imports of third-party dependencies without prompting the user.
- Run `./gradlew build` for a full build.
- Run `./gradlew lint` before handing off Kotlin changes. This runs both
  `ktlintCheck` and detekt.
- Use `./gradlew ktlintFormat` to apply ktlint formatting, then review the resulting diff. The repository pre-commit
  hook formats Kotlin sources and stops if formatting changes them so the changes can be staged explicitly.
- The Kotlin `.editorconfig` and detekt configuration enforce a 120-character maximum line length. Detekt builds on its
  default rules; the project-specific
  `ReturnCount` limit is four.
- Keep the detekt configuration in `config/detekt/detekt.yml` and formatting rules in `.editorconfig` rather than
  suppressing rules locally. Add a narrow suppression only when the code genuinely needs an exception and the reason is
  clear.

## Tests

- Put Kotlin tests under `src/test/kotlin` and run them with `./gradlew test`.
- Use `kotlin.test` assertions and test lifecycle annotations.
- Use MockK when a test needs mocks; do not introduce a second mocking library.
- Prefer small real collaborators or focused fakes when they make a test clearer than a mock. Keep tests deterministic
  and avoid network calls, real Spotify accounts, or the developer's local secrets.
- Unit testing should be limited to more complicated functionality, not every method needs a test. We should prefer
  module-level contract tests to exhaustive helper function tests.

## Kotlin design and readability

- Keep business intent visible at the top level of module files. A top-level function should make the important what/why
  decisions readable from its name, parameters, and control flow.
- Please minimize imports of third-party dependencies without prompting the user.
- Use `kotlin-logging` for application and support-tool logging instead of direct `println` calls. Keep log formatting
  and timestamps centralized in the SLF4J configuration.
- Avoid deeply nested helper functions that contain business-logic decisions. Extract helpers for discrete mechanical
  steps—such as parsing, encoding, request construction, batching, or mapping—while keeping orchestration and policy
  decisions in the clearer top-level function or service method that owns them.
- Prefer focused, explicit functions and data classes over clever abstractions. Preserve the existing service behavior
  when porting functionality from the TypeScript implementation.
- Keep HTTP/OAuth concerns in the HTTP and Spotify client modules, persistence behind the database/store boundary,
  playlist definitions in one place, and synchronization plus playlist updates in the service orchestration layer.
- Do not log client secrets, access tokens, authorization codes, or secret-file contents. Use the ignored
  `secrets.properties` file (or
  `SPOTIFY_BUTLER_SECRETS_FILE`) for local credentials.

## Local service conventions

- The local callback URI is exactly
  `http://127.0.0.1:8888/callback`; keep it consistent with Spotify application settings.
- Use the documented Gradle entrypoint from the repository root:
  `./kt/gradlew -p kt run`.
- The Vue app requires Node 24.12.0. From the repository root, run `nvm use` (the version is recorded in `.nvmrc`)
  before using npm; verify with `node --version` and `npm --version` if needed.
- Install and verify the Vue app with `npm --prefix vue install`, `npm --prefix vue run test`, and
  `npm --prefix vue run build`. Do not use the system Node installation for these commands.
- Keep generated build output, IDE files, and real secret properties untracked. The committed
  `secrets.properties.example` is the safe template.

## Durable design best practices

- Represent extensible domain behavior with immutable, serializable data models
  or sealed ASTs. Kotlin DSLs and builders may be authoring conveniences, but
  the immutable model—not mutable builder state, SQL text, or an enum—is the
  persisted, compared, versioned, and wire-level contract.
- For randomized behavior that must be reproducible, keep randomness outside
  databases: capture a cryptographically strong seed, derive deterministic ranks
  from canonical versioned inputs, and use explicit identity tie-breakers. Do
  not use SQLite `RANDOM()` or a seeded JVM PRNG as a generation contract.
- Version semantic inputs separately from implementation details. Persist the
  canonical recipe/domain revision, data/cache revision, and algorithm version
  with generated results. Performance optimizations may not change a revision's
  observable output.
- Make null behavior, range inclusivity, multi-valued dimensions, distinctness,
  quota admission, and ordering direction explicit in the model and tests.
  Avoid hidden behavior that depends on SQL planner order, insertion order, or
  `rowid`.
- Normalize data needed by supported domain behavior into typed projections and
  relation tables. Retain raw upstream JSON for compatibility, but do not make
  raw JSON the query or business-logic surface.

## Durable testing best practices

- Prefer module-level contract tests over exhaustive helper tests for business
  behavior. The strongest playlist-generation contract is real SQLite fixture
  data flowing through definition/recipe resolution, generation, generation
  storage, planning, and a recording mutation boundary, with exact ordered
  identifiers asserted at the end.
- Keep fixtures small, readable, deterministic, sanitized, and schema-versioned.
  Use meaningful symbolic track names, explicit boundary/null/duplicate data,
  and one expected identifier per line where practical. Never use real accounts,
  tokens, captures, or personal payloads.
- Golden tests must assert exact ordered results, not only sets, counts, or
  eligibility. During migrations, run legacy and new implementations over the
  same fixture; characterize inherently random legacy behavior with invariants
  while establishing fixed-seed goldens for the new implementation.
- Prove determinism across repeated runs, SQLite insertion-order permutations,
  database close/reopen, and pushdown-versus-non-pushdown execution. Add
  known-answer tests for canonical hashes, encoding, domain separation, and
  tie-breaking.
- Emit a human-readable INFO report for sanitized playlist-generation contract
  cases containing the normalized definition/recipe, seed and revisions,
  counts, and every selected song in final order. Reports are diagnostics only;
  assertions must remain independent of log text. Production logs should use
  identity and counts rather than private song lists.
- Test validation failures and complexity/resource limits as deliberately as
  successful recipes. Unsupported fields, ambiguous dimensions, invalid ranges,
  excessive ASTs, and arbitrary SQL must fail with stable actionable errors.

## Formatting and verification workflow

- Before any formatting check, always run the CLI autoformatter first (for
  Kotlin, `./gradlew ktlintFormat`) so formatting is deterministic and repeatable
  and manual fixups are minimized. Review the resulting diff, then run
  `./gradlew lint` and the appropriate focused tests.
- Keep lint/detekt and full-build verification at the final verification stage
  of a sequenced change unless a task explicitly requires an earlier check.
