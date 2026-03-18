# Actor Context Capabilities

**Status:** Current context snapshot plus timer design note.

**Purpose:** Define the actor-facing `ActorContext` capability surface and the intended contract for timer APIs before implementation.

**Precedence:** For implemented behavior, code wins. This document is narrower than [actor-semantics.md](./actor-semantics.md): it only covers actor-facing context capabilities. For timer APIs, this document is the planning source unless a later actor spec supersedes it.

## Summary

Current `ActorContext` is a loop-confined capability object. It exposes:

- identity: `name`, `label`
- self messaging/reply target: `self`
- loop assertion: `guardActorAccess()`
- lifecycle watch: `watch(...)`
- actor-local shutdown: `stopSelf(...)`

Planned timer capability should also live on context and should follow Akka-style keyed semantics:

- names: `once`, `repeated`, `cancel`, `cancelAll`
- same key replaces existing timer
- canceled or replaced timers must not deliver stale messages
- timers are actor-owned and auto-canceled on actor termination

## Current Context Contract

Today the public actor-facing context surface is:

```kotlin
interface ActorContext<Command : Any> {
    val name: String
    val label: String
    val self: ActorRef<Command>

    suspend fun guardActorAccess()
    suspend fun watch(
        actor: ActorRef<*>,
        onTerminated: (ActorTermination) -> Command,
    )
    suspend fun stopSelf(timeout: Duration? = null)
}
```

Current context rules extracted from code and actor specs:

- `ctx` is valid only on the actor loop coroutine
- context operations are loop-confined and must fail when used from leaked or detached coroutines
- `ctx.self` is the actor's normal `ActorRef<Command>` and may be used as `replyTo`
- actor-local shutdown must go through `ctx.stopSelf(...)`, not `ctx.self.shutdown()`
- `watch(...)` is one-shot, loop-confined, rejects self-watch, rejects new watches during shutdown, and is idempotent per watched actor
- successful watch registration guarantees eventual delivery through runtime-owned system delivery rather than normal user-mailbox pressure

## Context Design Rules

Context should expose actor-owned runtime capabilities, not raw coroutine primitives or infrastructure handles.

Rules:

- if a capability creates or mutates actor-owned runtime state, it belongs on `ctx`
- actor-facing capabilities should be loop-confined unless there is a strong reason otherwise
- direct one-off capabilities may be verbs on `ctx`
- a growing capability family should move under a namespace object such as `ctx.timers`

## Timer Capability

### Status

Not implemented.

### Goal

Support delayed and repeated self-messages without leaking coroutine `Job` management into actor code.

The current pattern of launching a coroutine and later sending to `ctx.self` is not the intended long-term API. Timers should be represented as actor-owned runtime state.

### API Shape

Use a timer namespace on context:

```kotlin
interface ActorContext<Command : Any> {
    val timers: ActorTimers<Command>
}

interface ActorTimers<Command : Any> {
    suspend fun once(key: Any, delay: Duration, command: Command)
    suspend fun repeated(key: Any, interval: Duration, command: Command)
    suspend fun cancel(key: Any): Boolean
    suspend fun cancelAll()
}
```

### Naming

Use:

- `once`
- `repeated`
- `cancel`
- `cancelAll`

Do not use:

- `scheduleOnce`
- `scheduleRepeated`
- `timer`
- coroutine `Job` handles

Reasoning:

- `once` and `repeated` describe timer behavior directly
- `timers` is the right namespace because this is a capability family
- `Job` would expose coroutine implementation details instead of actor semantics

### Timer Contract

Adopt Akka-style keyed timer semantics.

Rules:

- timers are identified by `key`
- `once(key, ...)` replaces any existing timer for that key
- `repeated(key, ...)` replaces any existing timer for that key
- replacement works across mode changes
  - `once` may replace `repeated`
  - `repeated` may replace `once`
- `cancel(key)` is idempotent
- `cancelAll()` cancels all active timers for the actor
- timers are auto-canceled when actor shutdown begins
- timers must not outlive actor termination

### Delivery Semantics

Timer delivery must enqueue a normal self-message. Timer callbacks must not run actor code directly.

Important rule:

- if a timer is canceled or replaced, stale messages from the old timer must not be delivered, even if they were already internally queued for delivery

This is the main semantic reason to use runtime-managed keyed timers instead of bare delayed `tell()` helpers.

Implementation implication:

- runtime must track timer generations or an equivalent suppression mechanism so canceled/replaced timer messages are dropped as stale

### Confinement and Lifecycle

Timer APIs should be loop-confined like `watch(...)` and `stopSelf(...)`.

Rules:

- actor code may create or cancel timers only from the actor loop
- new timers must be rejected once shutdown begins
- actor termination cancels all timers automatically
- timer firing must still respect the runtime's normal command delivery path

### Non-Goals

This timer capability does not imply:

- general-purpose scheduling for arbitrary targets
- exposing coroutine `Job` or `Deferred`
- direct callback execution outside actor message delivery

### Open Questions

Questions still worth deciding before implementation:

1. Should `repeated` be fixed-rate or fixed-delay?
2. Should `once(key, ZERO, command)` behave exactly like immediate self-send or still flow through timer machinery uniformly?
3. Should timer keys remain `Any`, or should the API prefer a dedicated `TimerKey` type for stronger clarity?
