# Semantic decisions

This file records the design choices implemented by the current actor runtime.

1. Keep a bounded user mailbox with fail-fast `tell()`.
   `tell()` is non-suspending and reports `MAILBOX_FULL` or `ACTOR_CLOSED`.

2. Keep external `ask()`, but model replies with `replyTo: ActorRef<T>`.
   `ask()` remains a convenience API layered on top of normal actor messaging by creating a
   temporary one-shot actor ref to receive the reply.

3. Use `ActorRef<T>` directly for request/reply protocols.
   Compatible actor refs can be used directly as `replyTo`, so actor-to-actor replies stay normal
   actor messaging.

4. Remove the old `ReplyChannel` / `ReplyRequest` / `Request` transport.
   The runtime now uses typed actor refs for replies plus internal ask bookkeeping.

5. Keep `ask()` one-shot.
   The first reply wins for the temporary ask reply actor ref; later replies are rejected.

6. Separate user commands from system events.
   Stop and watch notifications use a dedicated internal system queue.

7. Keep shutdown cooperative and first-wins.
   `shutdown(timeout)` requests stop, later callers share the same termination barrier, and the first timeout controls drain behavior.

8. Disallow blocking self-shutdown.
   Actor code must use `ctx.stopSelf(...)`; `self.shutdown()` fails fast on the actor loop.

9. Treat `CancellationException` as cancellation, not command failure.
   Cancellation still terminates the actor but does not become `ActorCommandFailed`.

10. Run teardown hooks in `NonCancellable`.
    `preStop` and `postStop` are cleanup hooks, not best-effort callbacks.

11. Make watch idempotent per watcher/target pair.
    Duplicate watch registration is a no-op and keeps the first mapper.

12. Guarantee watch delivery once registration succeeds.
    Termination notifications use the system queue so user mailbox pressure cannot drop them.

13. Keep failure-stop semantics.
    Handler failure stops the actor; restart remains external via watching/supervision logic.

14. Keep ordering guarantees minimal.
    User commands are FIFO among themselves, and pending system events run before pending user commands.
