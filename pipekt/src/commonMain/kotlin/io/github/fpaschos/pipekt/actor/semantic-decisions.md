**Recommended Decisions**

1. **Keep two user APIs, not one**
   Recommendation: keep `tell` fail-fast and add `send` only if you actually need producer backpressure.
   Why: this preserves simple actor semantics while allowing an explicit backpressured path later.
   Suggested semantics:
- `tell(command): Result<Unit>` = non-suspending, returns `MAILBOX_FULL` / `ACTOR_CLOSED`
- `ask(...)` = built on `tell`, request/reply only
- `send(...)` = optional future API, suspends until admitted or actor closes

2. **Separate system events from user commands**
   Recommendation: introduce a dedicated system queue and move stop/watch notifications there.
   Why: this fixes the main correctness issue without changing user mailbox semantics.
   Suggested semantics:
- user queue remains bounded
- system queue is non-droppable
- system events are processed before user commands

3. **Make shutdown a system event with first-wins semantics**
   Recommendation: replace `stopSignal` with `SystemEvent.Stop(timeout)`.
   Why: one control-plane path is easier to reason about.
   Suggested semantics:
- first shutdown request wins
- later shutdown callers only await termination
- `timeout` bounds draining queued user commands only, not total termination time

4. **Disallow blocking self-shutdown**
   Recommendation: an actor must not call `shutdown()` on itself.
   Why: otherwise it can deadlock waiting for its own termination.
   Suggested semantics:
- external callers may use `shutdown()`
- actor code stops itself via a `Stop` command or internal non-blocking stop request
- self-`shutdown()` throws `IllegalStateException`

5. **Treat cancellation as cancellation, not command failure**
   Recommendation: rethrow `CancellationException`; do not route it through `onCommandFailure`.
   Why: this matches Kotlin coroutine semantics and avoids false `ActorCommandFailed` errors.
   Suggested semantics:
- non-cancellation exceptions => command failure
- `CancellationException` => actor terminates due to cancellation
- `postStop` still runs

6. **Guarantee cleanup once termination starts**
   Recommendation: run teardown hooks in `NonCancellable`.
   Why: cancellation should not interrupt cleanup halfway through.
   Suggested semantics:
- `preStop` and `postStop` complete once shutdown/finalization begins
- parent/job cancellation still terminates the actor, but cleanup runs to completion

7. **Make `watch` idempotent per watched actor**
   Recommendation: one watcher-target relation only.
   Why: this is simpler and matches your stated intent.
   Suggested semantics:
- duplicate `watch(target, ...)` is a no-op
- first mapper wins
- watching an already terminated actor produces immediate notification

8. **Make successful watch registration mean guaranteed eventual notification**
   Recommendation: once `watch(...)` returns successfully, the runtime must not silently lose the termination signal.
   Why: otherwise the API lies.
   Suggested semantics:
- watch notifications never depend on user mailbox capacity
- if registration cannot guarantee delivery, `watch` should fail instead of silently succeeding

9. **Keep parent-scope cancellation as part of the contract**
   Recommendation: preserve ambient coroutine ownership.
   Why: it is idiomatic for Kotlin and already tested.
   Suggested semantics:
- parent cancellation terminates the actor
- current handler stops at next cancellation point
- actor state is not transactional; partial in-memory mutation is possible

10. **Document ordering minimally and explicitly**
    Recommendation: define only the order that matters.
    Why: trying to promise total ordering across system and user traffic adds complexity.
    Suggested semantics:
- user commands are FIFO among themselves
- system events are processed ahead of pending user commands
- no total FIFO guarantee between user and system traffic

**What I would implement**
- keep `tell` and current `ask`
- do not add `send` yet unless you have a concrete use case
- add a system queue
- move `shutdown` and watch notifications onto it
- fix `CancellationException`
- guard self-`shutdown()`
- make `watch` idempotent, first mapper wins
- add tests for these semantics

That is the smallest coherent design that fixes the correctness issues without over-expanding the API.