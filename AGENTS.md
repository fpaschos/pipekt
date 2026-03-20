# AGENTS.md

## Project Overview
- `pipekt` is a Kotlin Multiplatform monorepo for long-running data pipelines.
- Root modules are `pipekt`, `pipekt-actor`, and `pipekt-amqp`.
- The project is still experimental; prefer small, focused changes that preserve existing design direction.
- Treat `plans/` as a high-signal source of current architecture intent when it is relevant to the task.

## Repository Layout
- `pipekt/` contains the core pipeline DSL, contracts, and runtime pieces.
- `pipekt-actor/` contains actor-oriented concurrency primitives.
- `pipekt-amqp/` contains AMQP-specific integration code.
- Each module may include `specs/` for design notes or implementation planning.
- Kotlin sources are organized by source set under `src/`, with shared code primarily in `commonMain` / `commonTest` and JVM code in `jvmMain` / `jvmTest`.

## Working Style
- Keep changes surgical and aligned with existing naming and package structure.
- Prefer fixing root causes over adding compatibility shims or duplicate logic.
- Do not refactor unrelated code while addressing a focused task.
- Follow existing Kotlin style and favor clear, idiomatic APIs over clever abstractions.

## Kotlin Multiplatform Guidance
- Default to `commonMain` only when code is genuinely platform-agnostic.
- Keep JVM-only integrations and transport implementations in `jvmMain`.
- Introduce `expect` / `actual` only when multiple platforms need the same abstraction.
- Avoid prematurely abstracting code for platforms that are not implemented yet.

## Build, Test, and Formatting
- Use Gradle from the repo root via `./gradlew`.
- Prefer targeted tasks for the module you change before broader validation.
- Useful tasks:
  - `./gradlew :pipekt:compileKotlinJvm --rerun-tasks`
  - `./gradlew :pipekt:jvmTest`
- Ktlint is configured across subprojects; keep code format clean and consistent with existing sources.

## Dependency and Generated Files
- Do not commit generated output, local IDE files, or build artifacts.
- Respect the existing `.gitignore`; update it only if a new generated path is introduced by your change.
- If a task requires dependencies or generated sources, install or generate only what is needed for the touched module(s).

## Documentation
- Update nearby `README.md`, `specs/`, or KDoc when behavior or public API changes.
- For public Kotlin APIs, document important behavior, edge cases, and failure modes when they are not obvious from the code.

## Agent Notes
- Before making changes, check for any deeper `AGENTS.md` files inside the module you are touching.
- If the task involves source set placement, platform abstractions, or public Kotlin API design, prefer guidance consistent with the repository’s Kotlin Multiplatform patterns.
