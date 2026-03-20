# pipekt-actor

`pipekt-actor` is the actor runtime module for the `pipekt` project. It provides a Kotlin Multiplatform actor model with typed actor references, bounded user mailboxes, request/reply via `replyTo`, cooperative shutdown, and watch-based lifecycle notifications.

The module targets the same KMP setup as the rest of the project and is intended to hold the reusable concurrency/runtime layer rather than application-specific actor protocols.

## Monitoring

`pipekt-actor` now emits internal actor lifecycle logs through `log4k`.

The runtime logs actor start, stop requests, shutdown, startup failures, and command failures. Normal lifecycle events are emitted at `DEBUG`; startup and command failures are emitted at `ERROR`.

## Specs

The [`specs/`](./specs/) folder documents the runtime behavior and the implementation constraints for this module.

- [`actor-semantics.md`](./specs/actor-semantics.md): the current source-of-truth behavior of the runtime. It describes actor construction, lifecycle, mailbox admission, system-vs-user queue ordering, `ask()`/`replyTo`, watch delivery, shutdown, cancellation, and failure handling.
- [`semantic-decisions.md`](./specs/semantic-decisions.md): the condensed list of design decisions the runtime currently follows. Use this as the short technical requirements summary for why the runtime behaves the way it does.
- [`actor-implementation-plan-v2.md`](./specs/actor-implementation-plan-v2.md): the implementation checklist for the current actor model refactor. It captures the required behavior to implement and validate, including API changes, shutdown semantics, watch guarantees, and test coverage expectations.
- [`actor-context-capabilities.md`](./specs/actor-context-capabilities.md): the actor-facing capability surface of `ActorContext`, including current loop-confined operations and timer design guidance for future API work.

## Technical requirements

At a high level, the current actor runtime is expected to satisfy these requirements:

- bounded user mailbox with fail-fast `tell()`
- typed request/reply using `replyTo: ActorRef<T>`
- one-shot external `ask()` semantics
- separate internal system queue for stop and watch events
- cooperative, first-wins shutdown behavior
- guaranteed watch notification after successful registration
- cancellation treated as termination, not command failure
- failure-stop runtime semantics with cleanup hooks preserved
