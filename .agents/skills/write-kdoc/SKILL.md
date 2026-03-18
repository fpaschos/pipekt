---
name: write-kdoc
description: Writes and reviews KDoc for Kotlin API entry points (public/protected functions, constructors, properties, and types) with emphasis on behavior, edge cases, concurrency, failure modes, and examples. Use when adding/changing public API, preparing Dokka docs, documenting lambda parameters, or when code has complex logic requiring careful inline documentation.
---

# Write KDoc (Kotlin API documentation)

Use this skill to **write or improve KDoc** so library users can adopt the API quickly and safely.

Primary source: Kotlin API guidelines on informative documentation, especially **thoroughly documenting API entry points** and documenting lambdas and concurrency behavior.

## Quick workflow (entry points first)

1. **Identify entry points**: public/protected declarations that users call/instantiate/override (functions, constructors, properties, classes/interfaces, extension functions).
2. **Write the “what & why” first**: one crisp paragraph describing behavior and intent (not restating the signature).
3. **Specify inputs + invalid inputs**: what is valid, what happens when invalid, and any normalization.
4. **Specify outputs + invariants**: what is returned/emitted, ordering guarantees, and units/timezones.
5. **Specify failure modes**: exceptions, error return types, cancellation behavior, retries, fallbacks.
6. **Concurrency & threading** (if applicable): where it runs, parallelism, sequencing, and structured concurrency/cancellation.
7. **Examples**: at least one minimal usage example for non-trivial APIs.
8. **Inline documentation for complex parts**: document *non-obvious* invariants and algorithmic intent at the exact point it matters.

## What “good KDoc” must contain (API entry points)

### High-level description (first paragraph)

Include:
- **What it does** in domain terms (behavioral description).
- **Key constraints** (e.g., “id must be stable for the lifetime of the actor”).
- **Important trade-offs** (e.g., “optimized for throughput over fairness”).

Avoid:
- Restating the signature in prose (“Takes a String and returns…”).
- Implementation narration.

### Inputs: valid ranges and invalid inputs

For each parameter, cover:
- **Accepted formats/ranges** (including empty, nullability, and sentinel values).
- **What happens on invalid input** (throws? coerces? returns error?).
- **Side-effects** triggered by specific values (e.g., “timeout=0 disables retries”).

### Outputs and guarantees

Document:
- **Return value semantics** (what it represents, whether it’s stable, cached, lazily computed).
- **Ordering/consistency guarantees** (especially for streams/collections).
- **Performance characteristics** when relevant (big-O, allocations, IO, backpressure).

### Failure modes (include cancellation)

Document:
- **Exceptions** that may be thrown and under what conditions.
- **Error encoding** if using `Result`, `Either`, sealed errors, etc.
- **Cancellation behavior** for `suspend` functions and coroutine-based APIs:
  - Whether cancellation is propagated.
  - Whether partial effects can happen before cancellation is observed.

### Lambdas: clarify exception + concurrency behavior

If an entry point takes a lambda, document:
- **What the lambda is used for** (what it supplies/decides).
- **If the lambda throws**: does the API fail immediately, retry, wrap, or rethrow?
- **Invocation context** (same thread/coroutine? dispatcher?).
- **Parallelism**: can multiple invocations run concurrently?
- **Sequencing**: ordering guarantees between invocations.

## Inline documentation for complex parts (special care)

Inline documentation is required when the code has **non-obvious correctness constraints**. Prefer:
- **Small KDoc blocks on private helpers** that encode invariants and contracts.
- **Tight, intent-focused line comments** at the exact line where a subtle invariant is relied upon.
- **Named variables / extracted functions** before adding comments.

Inline documentation must explain **intent or constraints**, not “what the code does”.

### Use inline documentation for
- **Invariants** (“this map is keyed by canonicalized IDs”, “must remain monotonic”).
- **Edge-case handling** (“handles duplicates due to at-least-once delivery”).
- **Concurrency guarantees** (“single-writer; reads may be stale but monotonic”).
- **Protocol rules** (wire formats, compatibility quirks, versioning).
- **Performance-critical tricks** (why an optimization is safe, what it avoids).

### Avoid inline documentation for
- Obvious control flow, variable assignments, or direct translations of code.
- Repeating the KDoc (keep KDoc on entry points; inline notes only where subtle).

## Copy-paste templates

### Function / suspend function (public API)

```kotlin
/**
 * <What this does in domain terms>. <Why it exists / key intent>.
 *
 * <Key guarantees and constraints.>
 *
 * @param <name> <Expected values/format, including edge cases.>
 * @return <Meaning of the return value, invariants, ordering, units.>
 * @throws <Exception> <When it happens and whether partial effects are possible.>
 *
 * ### Concurrency
 * - <Thread/coroutine/dispatcher where it runs>
 * - <Parallelism + sequencing guarantees>
 * - <Cancellation behavior>
 *
 * ### Example
 * ```kotlin
 * // minimal usage
 * ```
 */
```

### Lambda parameter (callback / block)

```kotlin
/**
 * ...
 *
 * @param block <What the lambda provides/does>.
 *
 * ### `block` behavior
 * - **Exceptions**: <what happens if block throws>
 * - **Invocation context**: <same coroutine? dispatcher?>
 * - **Parallelism**: <can run concurrently?>
 * - **Ordering**: <sequencing guarantees>
 */
```

## Review checklist (use before merging public API)

- [ ] Entry point has a **behavior-first** first paragraph (no signature restatement).
- [ ] Parameters include **valid ranges** and **invalid input behavior**.
- [ ] Return/outputs include **semantics + guarantees** (ordering, units, stability).
- [ ] Failure modes include **exceptions/errors and cancellation**.
- [ ] Lambdas document **exceptions + invocation context + parallelism/sequencing**.
- [ ] At least one **minimal example** for non-trivial APIs.
- [ ] Complex sections have **intent-focused inline docs** (invariants/concurrency/protocol/perf), not narration.

## Additional resources (load only if needed)

- For detailed patterns and examples, see `references/entry-point-recipes.md`.
- For inline docs in tricky code (concurrency/protocol/perf), see `references/inline-complexity.md`.
