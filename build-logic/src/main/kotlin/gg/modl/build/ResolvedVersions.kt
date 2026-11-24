package gg.modl.build

import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.provider.Provider

fun Configuration.resolvedVersionOf(group: String, name: String): Provider<String> =
    incoming.resolutionResult.rootComponent.map { root ->
        reachableComponents(root)
            .mapNotNull { it.moduleVersion }
            .firstOrNull { it.group == group && it.name == name }
            ?.version ?: "absent"
    }

private fun reachableComponents(root: ResolvedComponentResult): Sequence<ResolvedComponentResult> = sequence {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
        val component = pending.removeFirst()
        if (seen.add(component)) {
            yield(component)
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending.addLast(it.selected) }
        }
    }
}
