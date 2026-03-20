Final implementation/planning list:

1. Keep the bounded user mailbox and current fail-fast admission model.
   `tell()` stays non-suspending and returns `MAILBOX_FULL` / `ACTOR_CLOSED`.

2. Keep external `ask()`, but redefine request/reply around Akka-style `replyTo`.
   `ask()` remains an external convenience API, not a backpressure API.

3. Use `ActorRef<T>` directly as the reply target type.
   Normal actor refs should be used directly as `replyTo`.

4. Remove the old request/reply model entirely.
   No compatibility layer.
   Migrate away from `ReplyChannel`, `ReplyRequest`, `Request`, and the old deferred reply transport.

5. Reimplement external `ask()` using an internal temporary one-shot actor ref.
   `ask()` creates a temporary reply actor, builds the command with `replyTo`, sends it, then awaits one reply or timeout.

6. Make temporary ask replies one-shot strict.
   First reply wins.
   Later replies fail/are rejected.

7. Allow actor-to-actor request/reply to use the same `replyTo` pattern.
   Actor protocols become explicit and typed, Akka-style.

8. Do not add `send()` yet unless a real producer-backpressure use case appears.
   We are not expanding the enqueue API in this pass.

9. Add a dedicated system queue.
   Stop requests and watch notifications move off the user mailbox.
   System events are non-droppable and processed ahead of user commands.

10. Replace `stopSignal` with a unified `SystemEvent.Stop(timeout)`.
    Shutdown becomes a system event, not a separate control channel.

11. Keep current shutdown timeout semantics.
    The timeout bounds draining already accepted user commands after shutdown starts.
    It does not bound total termination time.

12. Use first-wins shutdown semantics.
    The first successful stop request fixes the drain timeout.
    Later shutdown callers only wait for the same termination barrier.

13. Guard `ActorRef.shutdown()` against self-use.
    If called from the actor’s own loop, fail fast with `IllegalStateException`.

14. Add a non-blocking self-stop path for actor code.
    Actor code should request stop without awaiting termination.
    External callers keep using `shutdown()`.

15. Treat `CancellationException` as cancellation, not command failure.
    Re-throw it instead of routing it through command-failure handling.

16. Preserve cleanup during termination.
    `preStop` and `postStop` should complete once shutdown/finalization begins, even under cancellation.

17. Decide and implement whether `preStop` also runs on cancellation-driven termination.
    Recommendation: yes, so teardown semantics stay consistent across shutdown and cancellation.

18. Make `watch` idempotent per watched actor.
    One watcher-target relation only.
    Duplicate `watch(target, ...)` is a no-op.
    First mapper wins.

19. Make successful watch registration mean guaranteed eventual notification.
    Once `watch(...)` succeeds, termination notification must not be silently lost because of mailbox pressure.

20. Reject or clearly fail `watch()` if called while the watcher is already shutting down.
    Do not silently accept new watches during teardown.

21. Disallow self-watch for now.
    Fail fast rather than supporting confusing edge-case behavior.

22. Keep parent coroutine ownership semantics.
    Parent-scope cancellation still terminates the actor.
    Actor state remains non-transactional under cancellation.

23. Keep ordering guarantees minimal.
    User commands remain FIFO among themselves.
    System events take precedence over pending user commands.
    No total FIFO guarantee across user and system traffic.

24. Keep the default failure policy: handler failure stops the actor.
    Restart remains external, driven by watch/supervision logic rather than in-runtime restart.

25. Keep the current `onFailure` and `onUndelivered` hooks in this pass.
    Adapt them to the new request/reply model rather than removing them.

26. Preserve current external `ask()` failure semantics under the new reply model.
    Recommendation:
   - handler failure after acceptance -> `ActorCommandFailed`
   - accepted but dropped during shutdown/failure -> `ActorUnavailable(NOT_DELIVERED)`
   - timeout -> `ActorAskTimeout`

27. Define reply delivery semantics clearly.
    Recommendation:
   - temporary ask reply actor is one-shot
   - actor-backed `replyTo` uses normal `tell` semantics

28. Do not add `messageAdapter()` in this pass.
    It is a likely follow-up feature, not part of this refactor.

29. Define what happens if `preStop` or `postStop` throws.
    Recommendation: preserve the original terminal cause if one already exists, and do not let cleanup exceptions rewrite command-failure semantics unexpectedly.

30. Migrate all actor protocols/tests to the new `replyTo` model in one pass.
    No compatibility bridge; update all usages together.

31. Add tests for the revised semantics.
    At minimum:
   - self-shutdown guard
   - cancellation classification
   - `preStop`/`postStop` behavior under cancellation
   - guaranteed watch delivery under full user mailbox
   - idempotent watch
   - reject self-watch
   - reject/define watch during watcher shutdown
   - first-wins shutdown timeout
   - system-event priority
   - external `ask()` via temporary reply actor ref
   - actor-to-actor `replyTo` flow
   - one-shot temporary reply behavior
   - accepted-but-undelivered ask failure mapping

That is the final planning list to implement against.
