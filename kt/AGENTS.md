# Kotlin Spotify Butler

These instructions apply to the Kotlin service under `kt/`. It is an independent Gradle application and should remain
buildable without relying on the Node service.

## General modifications

- DO NOT run lint and unit tests on every step and modification. I like to sequence smaller changes then just run those
  manually before commit.

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
- Keep generated build output, IDE files, and real secret properties untracked. The committed
  `secrets.properties.example` is the safe template.
