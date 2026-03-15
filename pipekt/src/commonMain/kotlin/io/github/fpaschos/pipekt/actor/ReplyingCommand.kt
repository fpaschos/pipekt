package io.github.fpaschos.pipekt.actor

/**
 * Optional marker for commands that carry a reply handle.
 *
 * When [Actor.handle] throws and the command implements this interface, the base actor can
 * complete the pending reply exceptionally instead of leaving the requester suspended forever.
 */
interface ReplyingCommand {
    fun completeExceptionally(cause: Throwable)
}
