package io.github.fpaschos.pipekt.actor

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration

/**
 * Small runtime capability surface exposed to concrete actors.
 *
 * This intentionally does not model behavior switching or a general actor system.
 */
interface ActorContext<Command : Any> {
    /**
     * Semantic caller-provided name. This is not guaranteed to be globally unique.
     */
    val name: String

    /**
     * Process-local unique diagnostic label derived from [name], e.g. `worker#7`.
     */
    val label: String

    /**
     * Typed ref for the current actor.
     */
    val self: ActorRef<Command>

    /**
     * Spawns an owned child actor. Owned children are stopped during parent shutdown.
     */
    suspend fun <ChildCommand : Any> spawn(
        name: String,
        dispatcher: CoroutineDispatcher? = null,
        factory: (ActorContext<ChildCommand>) -> Actor<ChildCommand>,
    ): ActorRef<ChildCommand>

    /**
     * Observes a child previously created through [spawn].
     */
    fun watch(
        child: ActorRef<*>,
        onTerminated: (ChildTermination) -> Command,
    )
}

internal fun <Command : Any> createActorContext(
    scope: CoroutineScope,
    name: String,
): InternalActorContext<Command> = DefaultActorContext(scope = scope, name = name)

internal interface InternalActorContext<Command : Any> : ActorContext<Command> {
    val scope: CoroutineScope

    fun bind(actor: Actor<Command>)
}

private class DefaultActorContext<Command : Any>(
    override val scope: CoroutineScope,
    override val name: String,
) : InternalActorContext<Command> {
    private val actorInstanceId = nextActorInstanceId.incrementAndGet()

    override val label: String = "$name#$actorInstanceId"

    private lateinit var actor: Actor<Command>
    private val actorRef =
        object : ActorRef<Command> {
            override val name: String
                get() = this@DefaultActorContext.name

            override val label: String
                get() = this@DefaultActorContext.label

            override fun tell(command: Command): Result<Unit> = boundActor().actorRef.tell(command)

            override suspend fun shutdown(timeout: Duration?) {
                boundActor().actorRef.shutdown(timeout)
            }
        }

    override val self: ActorRef<Command>
        get() = actorRef

    override suspend fun <ChildCommand : Any> spawn(
        name: String,
        dispatcher: CoroutineDispatcher?,
        factory: (ActorContext<ChildCommand>) -> Actor<ChildCommand>,
    ): ActorRef<ChildCommand> = actor.spawnOwnedChild(name, dispatcher, factory = factory)

    override fun watch(
        child: ActorRef<*>,
        onTerminated: (ChildTermination) -> Command,
    ) {
        actor.watchChild(child, onTerminated)
    }

    override fun bind(actor: Actor<Command>) {
        check(!::actor.isInitialized) {
            "ActorContext for $label is already bound"
        }
        this.actor = actor
    }

    private fun boundActor(): Actor<Command> {
        check(::actor.isInitialized) {
            "ActorContext for $label is not bound yet"
        }
        return actor
    }
}
