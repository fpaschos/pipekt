## Entry point recipes (KDoc)

Use these patterns when documenting public/protected Kotlin APIs for Dokka/KDoc.

### 1) “Normalize + validate” APIs

Include:
- What is normalized (trim, case-fold, Unicode, path separators)
- What is rejected and how (exception vs error type)
- Whether normalization is stable/idempotent

Template additions:
- “Input is canonicalized by …”
- “Invalid inputs are rejected by …”

### 2) “May do IO” APIs

Include:
- Whether it touches network/disk
- Retry/backoff policy (or explicit “no retries”)
- Timeouts and units
- Offline/partial failure behavior

### 3) Streaming / Flow APIs

Include:
- Cold vs hot stream
- When it starts work (on collect? on creation?)
- Buffering/backpressure
- Emission ordering and whether duplicates are possible
- Cancellation and resource cleanup

Useful wording:
- “This flow is cold: work starts when collected.”
- “Emissions are ordered by …”
- “Duplicates may occur due to …”

### 4) Cache / memoization APIs

Include:
- Cache key definition
- Lifetimes/eviction
- Staleness rules
- Thread-safety / visibility guarantees

### 5) “Best-effort” APIs

Include:
- What “best-effort” means concretely
- Which failures are swallowed vs surfaced
- Observability (logging, metrics hooks)

### 6) “Exactly-once vs at-least-once” semantics

Include:
- Delivery guarantees and how duplicates are handled
- Idempotency requirements for user callbacks
- Reentrancy constraints

### 7) Lambdas called by the library

Add a dedicated section:

- **Exceptions**: immediate failure? retry? wrap? rethrow?
- **Invocation context**: caller thread? dispatcher? same coroutine?
- **Parallelism**: concurrent invocations possible?
- **Ordering**: deterministic ordering? per-key ordering?

### 8) Extension functions

Include:
- Receiver expectations (nullability, state, thread confinement)
- Side effects on receiver
- Whether it allocates or mutates

### 9) Constructors / builders

Include:
- Required invariants of constructor args
- Defaults and their consequences
- Lifecycle obligations (must call `close`? attach to scope?)

