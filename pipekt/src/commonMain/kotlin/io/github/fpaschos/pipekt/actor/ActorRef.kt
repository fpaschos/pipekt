package io.github.fpaschos.pipekt.actor

import kotlin.time.Duration

/**
 * Narrow external handle to an actor. Callers interact only through the ref's business API
 * and [shutdown]; they do not send raw mailbox commands or inspect internal state.
 *
 * Concrete refs extend this class and expose domain operations that delegate to the
 * underlying actor; [shutdownActor] is implemented to forward to the actor's
 * shutdown (e.g. [Actor.shutdownGracefully]).
 */
abstract class ActorRef {
    /**
     * Shuts down the actor. Default implementation delegates to [shutdownActor].
     * When this returns, the actor loop has terminated and no more commands are processed.
     *
     * @param timeout If non-null, graceful shutdown is attempted; if the timeout expires,
     *   the implementation may force-cancel and then await termination.
     */
    suspend fun shutdown(timeout: Duration? = null) {
        shutdownActor(timeout)
    }

    /** Forwards shutdown to the underlying actor. Called by [shutdown]. */
    protected abstract suspend fun shutdownActor(timeout: Duration?)
}
