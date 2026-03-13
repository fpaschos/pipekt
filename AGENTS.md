# AGENTS.md

## Unified Project Skills
Project skills live in Cursor Skill files so Codex and Cursor read the same source of truth.
This file lists project-local skill entry points only (no duplicates, no copied skill bodies).

### Local skills (canonical)
- pkt-core: Core authoring and review guidance for the Kotlin Multiplatform `pipekt` library (pipelines, operators, contracts, and compatibility).
  - file: `.cursor/skills/pkt-core/SKILL.md`
- pkt-tests: Testing standards for behavior changes in `pipekt`, including operator and pipeline contract coverage.
  - file: `.cursor/skills/pkt-tests/SKILL.md`
- pkt-plans: Rules for updating design docs in `plans/` and keeping implementation notes aligned with code changes.
  - file: `.cursor/skills/pkt-plans/SKILL.md`

### How to use
- If a user names one of the skills above or the task clearly matches it, use that skill.
- Open only the needed skill file(s); avoid loading unrelated docs.
- Skills are edited in one place only: `.cursor/skills/<name>/SKILL.md`.
- Keep this list as the single local index; do not duplicate skill definitions or instructions here.
