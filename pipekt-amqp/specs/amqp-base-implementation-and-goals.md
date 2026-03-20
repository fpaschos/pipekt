# AMQP Base Implementation And Goals

**Status:** Active technical guidance document.

**Purpose:** Define the initial scope, semantic goals, explicit non-goals, and base implementation shape for `pipekt-amqp` before code is rebuilt.

**Precedence:** This document is the current source of truth for the `pipekt-amqp` MVP direction. If implementation code and this document diverge, this document wins until a dedicated "current implementation" document exists for the module.

## Purpose and scope

`pipekt-amqp` is intended to be a **small coroutine-first AMQP client module** for Kotlin/JVM. The goal is not to create a full RabbitMQ framework or a broker management layer. The goal is to provide a narrow, explicit library for:

- publishing messages
- consuming messages
- manual settlement (`ack` / `nack`)
- coroutine-friendly integration (`suspend` and `Flow`)

The first implementation is intentionally narrow. It should prove the semantic contract and developer ergonomics before the module grows recovery, observability, or topology helpers.

This document covers:

- the core problem the module is solving
- the semantic contract for the first implementation
- where blocking exists and how the coroutine boundary should behave
- the minimum viable component layout
- explicit non-goals and deferred work

This document does not define:

- exchange / queue / binding declaration APIs
- routing policy
- serializer policy
- application retry policy
- dead-lettering policy
- framework-specific integration

---

## Problem statement

Most RabbitMQ client libraries for Kotlin are either:

- thin wrappers around the RabbitMQ Java client with little semantic guidance, or
- framework-heavy abstractions that mix transport, topology, serialization, lifecycle, and policy.

`pipekt-amqp` should sit in the middle:

- smaller than a framework
- more intentional than a raw Java wrapper
- explicit about what is transport behavior vs application policy

The module should provide a **clean coroutine-facing boundary** over RabbitMQ's JVM client while being honest about the underlying implementation reality:

- the RabbitMQ Java client is not coroutine-native
- broker I/O is still blocking and callback-driven underneath
- `suspend` and `Flow` make the API ergonomic; they do not make the protocol layer magically non-blocking

That distinction must remain visible in the design.

---

## Core design position

The base implementation should be a **semantic adapter**, not an infrastructure platform.

That means:

- define the public API and its guarantees first
- keep RabbitMQ-specific protocol mechanics behind a small implementation boundary
- do not build reconnection orchestration, topology DSLs, or policy engines before the basic contract feels correct

The initial implementation should optimize for:

- API clarity
- explicit delivery semantics
- low surface area
- correct cancellation and settlement behavior
- easy reasoning about the blocking boundary

It should not optimize for:

- complete broker feature coverage
- hidden automation
- opinionated policy
- operational sophistication on day one

---

## Base implementation goals

The first implementation should satisfy all of the following:

### 1. Small public surface

The public API should be explainable in a few minutes. At minimum:

- one client interface
- one Rabbit-backed implementation
- one delivery model
- one consumer options model

### 2. Coroutine-friendly usage

Publishing should be `suspend`.

Consumption should be exposed as a cold `Flow`.

That allows callers to compose transport behavior with normal coroutine tools:

- `collect`
- `buffer`
- `retry`
- `map`
- `flatMapMerge`
- structured cancellation

### 3. Explicit manual settlement

The first version should require explicit `ack` / `nack`.

This keeps delivery behavior visible and avoids premature opinionation around auto-ack or auto-retry semantics.

### 4. Honest blocking boundary

The module should not pretend to be fully non-blocking.

It should instead be explicit that:

- the public API is coroutine-friendly
- the underlying RabbitMQ driver remains blocking / callback-based
- blocking driver calls are isolated onto `Dispatchers.IO`

### 5. Existing topology assumption

The module assumes exchanges, queues, bindings, and routing keys already exist.

Topology and broker policy are application or infrastructure concerns, not part of the base implementation.

### 6. Minimal correctness before sophistication

The first implementation should get these right before adding bigger features:

- flow cancellation closes the consumer cleanly
- settlement is single-use
- consumer-side prefetch is configurable
- publish path behavior is explicit

---

## Non-goals for the base implementation

The following are intentionally out of scope for the first implementation:

- exchange / queue / binding declaration helpers
- serializer abstractions beyond raw `ByteArray`
- request/reply API
- transaction support
- batch publish API
- dead-letter abstractions
- retry policies
- framework adapters (Spring, Ktor, Micronaut, etc.)
- health endpoints
- metrics and tracing API
- automatic reconnection and resubscription
- consumer group or partition semantics

These items may become follow-up documents, but they should not leak into the MVP shape.

---

## Base semantic contract

The first implementation should expose a contract conceptually equivalent to:

```kotlin
interface AmqpClient : AutoCloseable {
    suspend fun publish(
        exchange: String,
        routingKey: String,
        body: ByteArray,
    )

    fun consume(
        queue: String,
        options: ConsumeOptions = ConsumeOptions(),
    ): Flow<Delivery>
}
```

With deliveries conceptually equivalent to:

```kotlin
data class Delivery(
    val body: ByteArray,
    val exchange: String,
    val routingKey: String,
    val redelivered: Boolean,
    val ack: suspend () -> Unit,
    val nack: suspend (requeue: Boolean) -> Unit,
)
```

### Required semantics

- `publish(...)` sends exactly one AMQP message attempt through the underlying driver.
- `consume(...)` returns a cold flow; each collection creates a distinct consumer session.
- deliveries are manual-ack by default.
- `ack` and `nack` are mutually exclusive and single-use.
- cancelling collection stops the current consumer session and closes associated resources.
- closing the client invalidates future publish or consume operations.

### Explicitly unspecified in the base version

The first implementation should not over-promise on:

- end-to-end exactly-once behavior
- broker durability guarantees beyond what the broker is configured to do
- transparent publish retries
- transparent consumer recovery after connection loss
- stable ordering across reconnects or across multiple consumers

If the implementation does not guarantee a behavior, the docs should say so directly.

---

## The blocking boundary

This module must be documented and implemented as a coroutine-facing wrapper over a non-coroutine-native driver.

### What is actually blocking

For a RabbitMQ Java client-based implementation, the blocking work includes:

- connection creation
- channel creation
- `basicPublish(...)`
- `basicAck(...)`
- `basicNack(...)`
- channel close
- connection close

These are JVM driver calls that may block on:

- socket I/O
- backpressure from the broker
- client-internal coordination
- connection shutdown

### What is not blocking by itself

These language constructs are not the blocking work:

- `suspend`
- `Flow`
- `callbackFlow`
- `withContext(...)`

They only define how the module exposes or schedules the underlying work.

### Required boundary behavior

The base implementation should follow this rule:

- any blocking driver call that happens on behalf of a coroutine should run on `Dispatchers.IO`

That means the module is:

- coroutine-friendly
- cancellation-aware at the library boundary
- not truly non-blocking at the protocol layer

This distinction should appear in public docs and internal technical notes.

---

## Internal concurrency model

The base implementation should use a **coroutine actor-style internal design**.

This is an implementation decision, not just a preference:

- internal coordination should use Kotlin coroutine `Channel`
- simple lifecycle and settlement flags should use atomics
- public consumption should remain `Flow`
- `Mutex` should not be part of the base implementation strategy
- `pipekt-actor` should not be used inside this module

### Rationale

The module is a small transport client with explicit ownership boundaries:

- one client owns connection lifecycle
- one publish path owns the publish channel
- each consumer session owns its consumer channel

That maps more naturally to single-owner coroutine loops than to shared mutable state protected by locks.

The design goal is:

- actor-style sequencing for mutable transport state
- atomics for small one-bit or numeric state
- `Flow` for the public streaming API

### Required internal primitives

The implementation should rely on these coroutine primitives:

- Kotlin `Channel` for internal command passing
- coroutine scopes and launched jobs for actor loops
- `callbackFlow` or `channelFlow` for bridging consumer callbacks into `Flow`
- atomics for close flags, settlement guards, and similar single-owner state markers

### Forbidden internal coordination style for the base version

The following should not be the default coordination mechanism in the base implementation:

- `Mutex`
- broad shared mutable state accessed from many coroutines
- `pipekt-actor` abstractions

This is not because those tools are universally wrong. It is because this module should remain:

- lightweight
- coroutine-native
- explicit about state ownership
- independent from the rest of `pipekt` actor infrastructure

### Ownership model

The internal coroutine structure should follow this ownership pattern:

- one connection actor coroutine owns connection state
- one publisher actor coroutine owns the publish AMQP channel
- one consumer session coroutine owns each consumer AMQP channel

Conceptually:

```text
RabbitAmqpClient
  -> connection actor
  -> publisher actor
  -> consumer session actor(s)
```

Each actor receives commands through a Kotlin `Channel` and is the only coroutine allowed to mutate the transport state it owns.

### Publisher path implications

Concurrent `publish(...)` calls should not coordinate through a mutex around a shared AMQP channel.

Instead:

- callers send publish commands to a publisher actor
- the publisher actor serializes access to the single publish AMQP channel
- completion or failure is returned to the caller through a deferred reply mechanism or equivalent command response path

This gives ordered publish access without lock-based coordination.

### Consumer path implications

The consumer-side public API should remain `Flow<Delivery>`.

Internally:

- the RabbitMQ Java callback is bridged into `callbackFlow` or `channelFlow`
- each flow collection owns one consumer session and one AMQP channel
- delivery settlement should use an atomic single-settlement guard

This keeps the public API ergonomic while preserving strict session ownership internally.

### Atomics guidance

Atomics should be used for small, explicit state only. Appropriate examples include:

- client closed flag
- consumer session closed flag
- single-settlement guard on a delivery
- connection generation/version markers if recovery is added later

Atomics should not be used as a substitute for full command sequencing. Ordered state transitions belong to actor loops driven by Kotlin `Channel`.

### Public API rule

Even though the implementation uses Kotlin `Channel` internally, the public consume API should remain `Flow`.

The distinction is:

- internal `Channel` for coordination
- public `Flow` for consumption

This should remain the default design stance unless a future document justifies a different public contract.

---

## Base implementation shape

The initial module should remain small. A reasonable first file/component layout is:

- `AmqpClient.kt`
  - public interface
  - public models (`Delivery`, `ConsumeOptions`, config)
  - public exceptions if needed
- `RabbitAmqpClient.kt`
  - RabbitMQ-backed implementation
  - publish path
  - consumer flow path
  - lifecycle and shutdown

The implementation should avoid introducing extra abstraction layers unless they solve a real, immediate problem.

For the base version, this means:

- no internal broker protocol abstraction for the sake of abstraction
- no synthetic connection manager hierarchy
- no recovery state machine
- no transport-independent SPI

If a seam is introduced, it should be justified by one of:

- a real public contract boundary
- a clear testing need that cannot be addressed more simply
- a concrete reduction in complexity

---

## Connection and channel ownership

The base implementation should use **one long-lived AMQP connection per client instance**.

This is the default ownership model:

- one `AmqpClient`
- one AMQP connection
- one publish channel
- one consumer channel per active consumer session

Conceptually:

```text
AmqpClient
  -> 1 Connection
  -> 1 publish Channel
  -> N consumer Channels
```

### Default rule

The module should scale **with channels before connections**.

That means:

- do not introduce a connection pool in the base implementation
- do not assume one connection per publish or one connection per consumer
- treat channels as the normal concurrency unit

### Why this is the default

RabbitMQ is designed for channels to be the lightweight multiplexed unit on top of a connection.

Compared to channels, connections are relatively expensive:

- they open separate TCP sockets
- they consume more broker resources
- they increase heartbeat and recovery overhead
- they create more operational complexity during reconnect storms

For most application workloads, increasing channels and consumers is the correct first scaling move.

### Base concurrency implications

The ownership model implies:

- the publish path should start with a single dedicated publish channel
- concurrent publishing on that channel must be serialized or otherwise coordinated
- each consumer session should own its own channel

The first version should prefer a simple serialized publish path over premature pooling.

### When more than one connection is justified

Additional connections should be introduced only for a concrete reason, such as:

- isolation between unrelated workloads
- isolation between publishing and consuming failure domains
- separate credentials or virtual hosts
- measured throughput limits on a single connection
- application architecture that already scales across multiple processes or service instances

More connections are therefore an explicit optimization or isolation choice, not the default scaling strategy.

### Scaling stance for the module

The recommended scaling order is:

1. start with one client and one connection
2. add consumer channels as needed
3. tune consumer prefetch
4. scale the application horizontally with more service instances
5. only then consider multiple connections per process if measurements justify it

This should remain the documented default until the module has enough operational evidence to justify a different recommendation.

---

## Publish path requirements

The first publish implementation should be simple and explicit.

### Base behavior

- one publish call results in one underlying driver publish attempt
- the method is `suspend`
- the actual driver call is isolated to `Dispatchers.IO`

### Initial constraints

- message body is `ByteArray`
- exchange and routing key are supplied directly by the caller
- message properties can be omitted initially or added as a small optional model if necessary

### Deferred publish concerns

The following may be added later, but should not be required for the base version:

- publisher confirms
- publish retries
- channel pooling
- confirm listeners
- mandatory flag / return handling

If confirms are later added, they should be documented as a change in publish semantics rather than slipped in implicitly.

---

## Consumer path requirements

The consumer side is where the first implementation gains most of its ergonomic value.

### Base behavior

- `consume(...)` returns a cold `Flow<Delivery>`
- each collection opens a dedicated consumer session
- the consumer uses manual acknowledgements
- prefetch is configurable

### Implementation boundary

The RabbitMQ Java client is callback-driven on the consumer side. The base implementation should bridge that callback model into Kotlin using `callbackFlow`.

Conceptually:

```text
RabbitMQ Java callback thread
    -> callbackFlow
    -> Flow<Delivery>
```

### Required consumer semantics

- collector cancellation triggers consumer cancellation and resource cleanup
- `ack()` and `nack()` are settlement operations on the underlying AMQP delivery tag
- settlement after channel closure should fail explicitly, not silently
- double settlement should fail explicitly

### Deferred consumer concerns

These are not required for the first implementation:

- automatic reconnect and re-subscribe
- consumer-side retry policies
- poison-message handling
- dead-letter helpers
- bounded overflow strategies beyond what `Flow`/collector composition already provides

---

## Error handling strategy

The base implementation should keep error handling simple and visible.

### Recommended error split

- connection/setup failures
- publish failures
- consumption/setup failures
- delivery settlement failures

Whether these are modeled as a small exception hierarchy or a small sealed error family is an implementation choice, but the external behavior should stay readable.

### Rules

- do not encode application-level retry policy in the transport library
- do not swallow channel/connection failures silently
- do not convert every failure into framework-style lifecycle state machinery
- prefer direct failure over hidden automation in the base version

This module should fail clearly before it becomes “helpful.”

---

## Lifecycle expectations

The client lifecycle should be explicit.

### Base rules

- the client owns its RabbitMQ connection resources
- `close()` is idempotent
- closing the client closes publisher and consumer resources owned by that client
- future operations after close should fail explicitly

### Open question for later hardening

The base implementation does not need to solve every in-flight close race, but it should document that:

- settlement or publish operations racing with shutdown may fail
- callers should treat shutdown as a terminal lifecycle boundary

---

## Testing expectations for the base version

The first implementation does not need a large test harness, but it does need focused tests around the contract.

Priority test areas:

- publish delegates one message per call
- consumer collection creates a consumer session
- collector cancellation closes the consumer session
- `ack` settles exactly once
- `nack` settles exactly once
- double settlement fails
- prefetch is applied when configured

If integration tests are added early, they should remain narrow and prove transport behavior rather than topology or policy behavior.

---

## Follow-up phases after the base version

Once the semantic adapter feels correct, follow-up work can be staged in this order:

### Phase 1: Contract hardening

- add richer publish properties if needed
- tighten error documentation
- add integration tests against a real broker

### Phase 2: Delivery guarantees

- add publisher confirms
- document exactly what publish success means
- add clearer settlement failure semantics

### Phase 3: Recovery

- add connection/channel recovery strategy
- define whether `consume(...)` fails, completes, or transparently retries on disconnect
- make that behavior a documented contract, not an incidental implementation detail

### Phase 4: Operational concerns

- logging hooks
- metrics/tracing hooks
- optional health exposure

Recovery and observability should be added only after the base API and semantic promises are stable.

---

## Recommended implementation stance

The base implementation should be intentionally boring.

That means:

- raw payloads first
- explicit caller-owned routing keys
- explicit manual settlement
- no hidden topology creation
- no disguised framework behavior

The module should first answer:

- is the API small and clear?
- are the delivery semantics understandable?
- is the coroutine boundary honest?
- can callers compose it naturally?

If the answer is yes, then the module is ready for the next layer of sophistication.
