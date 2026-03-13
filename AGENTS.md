# AGENTS.md

## Unified Project Skills

Project skills live in Cursor Skill files so Codex and Cursor read the same source of truth.
This file lists project-local skill entry points only (no duplicates, no copied skill bodies).

### Local skills (canonical)

- pkt-code: Core authoring and review guidance for the Kotlin Multiplatform `pipekt` library (pipelines, operators, contracts, and compatibility).
  - file: `.cursor/skills/pkt-code/SKILL.md`
- pkt-tests: Testing standards for behavior changes in `pipekt`, including operator and pipeline contract coverage.
  - file: `.cursor/skills/pkt-tests/SKILL.md`
- pkt-kotlin-ask: Senior Kotlin specialist investigator for in-depth code questions, patterns, and correctness; uses Context7 and `gradle/libs.versions.toml` for library context; proposes skill/AGENTS.md updates when a general pattern emerges.
  - file: `.cursor/skills/pkt-kotlin-ask/SKILL.md`

### How to use

- If a user names one of the skills above or the task clearly matches it, use that skill.
- Open only the needed skill file(s); avoid loading unrelated docs.
- Skills are edited in one place only: `.cursor/skills/<name>/SKILL.md`.
- Keep this list as the single local index; do not duplicate skill definitions or instructions here.
