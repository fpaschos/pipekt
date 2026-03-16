# Actor semantics

This document describes the behavior currently implemented in [`Actor.kt`](./Actor.kt), [`ActorContext.kt`](./ActorContext.kt), [`ActorRef.kt`](./ActorRef.kt), and [`RequestReply.kt`](./RequestReply.kt).

The source code is the single source of truth. If this file and the code disagree, the code wins.

## Summary

| Area | Current behavior |
| --- | --- |
| Construction | `spawn(...)` creates an actor instance, a process-local unique `label`, an internal `ActorRuntime`, and a standalone `DefaultActorRef`. |
| Identity | `name` is caller-provided and not unique. `label` is `name#id` and is unique within the process. |
| Actor shape | `Actor` defines behavior only: `postStart`, `handle`, `preStop`, `postStop`, `onCommandFailure`, and `onUndeliveredCommand`. |
| Mailbox | The runtime uses one `Channel<MailboxEvent<Command>>`. User commands and internal watch notifications go through the same mailbox. |
| Execution model | `handle(ctx, command)` runs on one loop coroutine. The base runtime does not execute multiple commands concurrently. |
| Admission | `tell()` accepts work only while lifecycle is `RUNNING`. Otherwise it fails with `ActorUnavailable(ACTOR_CLOSED)`. A full open mailbox fails with `ActorUnavailable(MAILBOX_FULL)`. |
| Startup | `postStart(ctx)` runs before lifecycle becomes `RUNNING`. `spawn(...)` waits for startup to complete successfully before returning the ref. |
| Startup failure | If `postStart(ctx)` throws, startup fails and `spawn(...)` fails with that exception. |
| Stop request during startup | If a stop request is observed after `postStart(ctx)` returns but before `RUNNING` is published, startup fails with a `CancellationException`, `preStop(ctx)` runs, and the actor never reaches `RUNNING`. |
| Shutdown | `shutdown(timeout)` sends a stop signal and waits for termination. Shutdown is cooperative: it does not cancel a running handler. |
| Shutdown sequence | When the loop handles a stop signal, it sets lifecycle to `SHUTTING_DOWN`, closes the mailbox, runs `preStop(ctx)`, then drains mailbox events. If draining exceeds the optional timeout, remaining user commands are dropped as undelivered. |
| Mailbox closure | If the loop observes the mailbox closed and drained through `onReceiveCatching`, it moves to `SHUTTING_DOWN` and exits the loop. |
| Termination | In `finally`, lifecycle becomes `SHUTDOWN`, `postStop(ctx)` runs, termination is published to watchers, the termination barrier completes, and the owned scope job is cancelled. |
| Handler failure | If `handle(...)` throws, `onCommandFailure(...)` runs first, the mailbox is closed with the exception, queued user commands are reported as undelivered, and the actor terminates. |
| Undelivered commands | Only queued `MailboxEvent.UserCommand` values are reported through `onUndeliveredCommand(ctx, command, NOT_DELIVERED)`. Queued watch notifications are discarded silently. |
| Request/reply | `ask()` creates a deferred reply channel, builds a command, sends it with `tell()`, and waits with timeout. Success returns `Result.success(reply)`. Timeout returns `ActorAskTimeout`. |
| Context confinement | `ActorContext.watch(...)` checks that it is called from the actor loop job. Off-loop use throws `IllegalStateException`. |
| Watching | `watch(ref)` only accepts `DefaultActorRef` instances created by this runtime. Other refs fail immediately. |
| Watch state | Each watched actor stores either active watcher registrations or a terminal `ActorTermination`. |
| Watch notification timing | If `watch(ref)` is called after the target already terminated, the runtime immediately tries to enqueue a watch notification into the watcher mailbox. |
| Watch delivery | A watch notification is delivered as an internal mailbox event. When handled, the watcher context maps it to a normal command and runs that command through `handle(...)`. |
| Watch registration lifetime | Current watch registrations are one-shot. After a watch notification is mapped once, that registration is removed from the watcher context. |
| Ownership model | There is no parent/child ownership API in this layer. Watching another actor does not imply lifecycle ownership. |

## Hook behavior

| Hook | Current behavior |
| --- | --- |
| `postStart(ctx)` | Runs on the loop before `RUNNING` is published. |
| `preStop(ctx)` | Runs on the loop when a stop signal is processed, and also on the stop-during-startup path described above. |
| `postStop(ctx)` | Runs on the loop in the `finally` block before termination is published. |
| `onCommandFailure(ctx, command, cause)` | Runs on the loop after `handle(...)` throws and before the actor terminates. Default behavior fails reply-bearing commands with `ActorCommandFailed`. |
| `onUndeliveredCommand(ctx, command, reason)` | Runs when queued user commands are dropped without reaching `handle(...)`. Default behavior fails only reply-bearing commands with `ActorUnavailable`. |

## Notes

- `shutdown(timeout)` does not guarantee that termination happens within `timeout`. The timeout only bounds the mailbox-drain block after the stop signal is processed.
- The runtime uses `stopSignal.trySend(...)`. Multiple shutdown callers share the same termination barrier, but later stop requests may be ignored if the stop-signal channel is already full.
- Watch notification enqueue uses `mailbox.trySend(...)` and ignores the result. If the watcher mailbox is closed or full, the notification is dropped.
- Top-level `spawn(...)` derives its parent scope from `currentCoroutineContext()`, so parent coroutine cancellation still terminates the actor runtime.
