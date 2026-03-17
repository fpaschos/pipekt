# Actor semantics

This document describes the behavior currently implemented in [`Actor.kt`](../src/commonMain/kotlin/actor/Actor.kt), [`ActorContext.kt`](../src/commonMain/kotlin/actor/ActorContext.kt), [`ActorRef.kt`](../src/commonMain/kotlin/actor/ActorRef.kt), and [`RequestReply.kt`](../src/commonMain/kotlin/actor/RequestReply.kt).

The source code is the single source of truth. If this file and the code disagree, the code wins.

## Summary

| Area | Current behavior |
| --- | --- |
| Construction | `spawn(...)` creates an actor instance, a process-local unique `label`, an internal runtime, and a `DefaultActorRef`. |
| Identity | `name` is caller-provided and not unique. `label` is `name#id` and is unique within the process. |
| Execution model | `handle(ctx, command)` runs on one loop coroutine. The runtime does not execute multiple commands concurrently. |
| User admission | `tell()` accepts work only while lifecycle is `RUNNING`. Otherwise it fails with `ActorUnavailable(ACTOR_CLOSED)`. A full mailbox fails with `ActorUnavailable(MAILBOX_FULL)`. |
| Queues | User commands and system events are separated. User commands go through a bounded mailbox. Internal stop/watch traffic goes through an unbounded system queue. |
| Ordering | User commands remain FIFO among themselves. Pending system events are processed before pending user commands. There is no total FIFO guarantee across the two queues. |
| Startup | `postStart(ctx)` runs before lifecycle becomes `RUNNING`. `spawn(...)` waits for successful startup before returning the ref. |
| Stop during startup | If stop is requested after `postStart(ctx)` returns but before `RUNNING` is published, startup fails with `CancellationException`, `preStop(ctx)` runs, and the actor never reaches `RUNNING`. |
| Shutdown API | `shutdown(timeout)` requests stop and waits for termination. It is invalid to call it from the actor's own loop coroutine. Actor code must use `ctx.stopSelf(...)` instead. |
| Shutdown timeout | `timeout` only bounds draining already accepted user commands after stop begins. It does not bound total termination time or interrupt the current handler. |
| Concurrent shutdowns | First stop request wins. Later shutdown callers only wait on the same termination barrier. |
| Shutdown sequence | When `SystemEvent.Stop` is handled, lifecycle becomes `SHUTTING_DOWN`, the user mailbox closes, `preStop(ctx)` runs, then queued user commands are drained. If drain times out, remaining queued user commands are dropped as `NOT_DELIVERED`. |
| Termination | In `finally`, lifecycle becomes `SHUTDOWN`, `postStop(ctx)` runs, termination is published to watchers, the termination barrier completes, and the owned scope job is cancelled. |
| Cancellation | `CancellationException` is treated as coroutine cancellation, not command failure. The actor still terminates, but accepted requests do not become `ActorCommandFailed` just because the runtime was cancelled. |
| Cleanup | `preStop(ctx)` and `postStop(ctx)` run in `NonCancellable`. `preStop` is executed at most once. |
| Handler failure | If `handle(...)` throws a non-cancellation failure, `onCommandFailure(...)` runs, the current ask reply is failed with `ActorCommandFailed`, queued ask replies are failed as `NOT_DELIVERED`, and the actor terminates. |
| Undelivered commands | Dropped queued user commands are reported through `onUndeliveredCommand(ctx, command, NOT_DELIVERED)`. If a dropped command came from external `ask()`, that caller fails with `ActorUnavailable(NOT_DELIVERED)`. |
| Request/reply | Commands that expect a reply carry `replyTo: ReplyRef<T>`. External `ask()` creates a temporary one-shot `ReplyRef`, sends the command, and waits with timeout. The first reply wins. |
| Actor-to-actor replies | `ActorRef<Command>` extends `ReplyRef<Command>`, so a normal actor ref can be used directly as `replyTo` when protocols line up. |
| Watching | `watch(ref)` is loop-confined, accepts only `DefaultActorRef`, rejects self-watch, and rejects new registration while the watcher is shutting down. |
| Watch semantics | Watches are idempotent per watcher/target pair while active. Duplicate `watch(target, ...)` calls are a no-op and keep the first mapper. |
| Watch delivery | Successful watch registration guarantees that target termination is enqueued onto the watcher system queue instead of competing with user mailbox capacity. If the target already terminated, notification is enqueued immediately. |
| Watch lifetime | A watch remains active until one termination notification is delivered and mapped back into a normal command. After that it is removed from the watcher context. |
| Ownership model | There is no parent/child ownership API in this layer. Watching another actor does not imply restart or automatic lifecycle ownership. |

## Hook behavior

| Hook | Current behavior |
| --- | --- |
| `postStart(ctx)` | Runs on the loop before `RUNNING` is published. |
| `preStop(ctx)` | Runs once, in `NonCancellable`, when cooperative stop starts or when cancellation/failure terminates the actor. |
| `postStop(ctx)` | Runs in `NonCancellable` from `finally` before termination is published. |
| `onCommandFailure(ctx, command, cause)` | Runs on the loop after a non-cancellation `handle(...)` failure and before the actor terminates. Default behavior is no-op. |
| `onUndeliveredCommand(ctx, command, reason)` | Runs when queued user commands are dropped without reaching `handle(...)`. Default behavior is no-op. |

## Notes

- `ask()` still is not mailbox backpressure. It first tries normal admission, then waits for a reply.
- Parent coroutine cancellation still terminates spawned actors because `spawn(...)` derives ownership from `currentCoroutineContext()`.
- Actor state is not transactional. Cancellation can leave in-memory state partially updated if a handler mutates state before a suspension point.
