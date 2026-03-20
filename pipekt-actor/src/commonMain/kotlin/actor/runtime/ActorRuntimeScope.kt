package io.github.fpaschos.pipekt.actor.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal data object OwnedActorScope :
    AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<OwnedActorScope>
}

internal fun createActorScope(
    parentScope: CoroutineScope,
    name: String,
    dispatcher: CoroutineDispatcher?,
): CoroutineScope {
    val parentContext = parentScope.coroutineContext
    val childJob = SupervisorJob(parentContext[Job])
    var scopeContext = parentContext + childJob + CoroutineName(name) + OwnedActorScope
    if (dispatcher != null) {
        scopeContext += dispatcher
    }
    return CoroutineScope(scopeContext)
}
