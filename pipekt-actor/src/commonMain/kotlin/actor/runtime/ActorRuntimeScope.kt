package io.github.fpaschos.pipekt.actor.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

internal fun createActorScope(
    parentScope: CoroutineScope,
    name: String,
    dispatcher: CoroutineDispatcher?,
): CoroutineScope {
    val parentContext = parentScope.coroutineContext
    val childJob = SupervisorJob(parentContext[Job])
    var scopeContext = parentContext + childJob + CoroutineName(name)
    if (dispatcher != null) {
        scopeContext += dispatcher
    }
    return CoroutineScope(scopeContext)
}
