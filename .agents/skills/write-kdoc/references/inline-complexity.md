## Inline documentation for complex parts (guidelines)

Write inline documentation only when it reduces the chance of future breakage by explaining **constraints that are not obvious from code**.

### Preferred order of fixes

1. Rename variables to encode intent.
2. Extract a helper function with a precise name.
3. Add a small KDoc to the helper (if it has a contract).
4. Add a short inline comment only where the subtlety occurs.

### Inline docs should capture

- **Invariant**: A condition that must always hold.
- **Precondition**: A condition the caller must satisfy.
- **Postcondition**: A condition the function guarantees.
- **Why this is safe**: For optimizations or tricky control flow.
- **Concurrency contract**: Thread/coroutine confinement, visibility, ordering.
- **Protocol/interop rule**: Compatibility quirks, version constraints, external expectations.

### When you MUST add inline docs

- A correctness requirement is *implicit* (not enforced by types/tests).
- The code relies on a non-obvious property (monotonic clock, stable ordering, idempotency).
- There is a deliberate deviation from “obvious” code because of performance or a bug workaround.
- The code is intentionally not “clean” because of external constraints (wire format, platform bug, legacy behavior).

### When you MUST NOT add inline docs

- To narrate straightforward steps (“increment i”, “loop over items”).
- To explain language features the team already knows (e.g., `let`, `also`) unless used in a subtle way.
- To duplicate KDoc already present on the entry point.

### Comment shapes that work well

- **Invariant comment** (one line, near the invariant use):
  - “Invariant: `cursor` always points to the next unread byte.”

- **Concurrency comment** (one line, near shared state):
  - “Single-writer: only mutated on the actor’s mailbox; readers may observe stale values.”

- **Protocol comment** (one line, near parsing/serialization):
  - “Protocol: field order is significant for signature verification.”

- **Optimization justification** (1–2 lines, near the optimization):
  - “Avoid allocation in hot path; safe because `segments` is immutable after construction.”

### Examples (good vs bad)

Bad (narration):

```kotlin
// Iterate over items and add them to result
for (item in items) {
    result.add(item)
}
```

Good (constraint):

```kotlin
// Invariant: `dedupe` must happen before ordering to keep stable sort keys.
val unique = items.distinctBy(::stableKey)
return unique.sortedBy(::stableKey)
```

Bad (restates code):

```kotlin
// If the timeout is 0, return immediately
if (timeoutMs == 0L) return null
```

Good (explains non-obvious policy):

```kotlin
// Policy: timeout=0 disables waiting; callers use this for non-blocking polling.
if (timeoutMs == 0L) return null
```

### Using private helper KDoc for contracts

If a private function has a subtle contract, document it with KDoc even if it’s private.

```kotlin
/**
 * Computes the canonical key used for deduplication.
 *
 * Contract: must be stable across process restarts and independent of locale/timezone.
 */
private fun stableKey(item: Item): String = ...
```

