package gg.modl.build

import org.gradle.api.Project
import org.gradle.api.tasks.AbstractCopyTask

fun Project.pluginDescriptorProperties(): Map<String, Map<String, String>> = mapOf(
    "plugin" to mapOf(
        "id" to requiredGradleProperty("plugin.id"),
        "name" to requiredGradleProperty("plugin.name"),
        "version" to version.toString(),
        "author" to requiredGradleProperty("plugin.author"),
        "description" to requiredGradleProperty("plugin.description"),
        "url" to requiredGradleProperty("plugin.url"),
    ),
    "project" to mapOf(
        "groupId" to group.toString(),
    ),
)

fun AbstractCopyTask.expandPluginDescriptor(properties: Map<String, Map<String, String>>, filePattern: String) {
    inputs.properties(properties.flatMap { (scope, values) -> values.map { (key, value) -> "$scope.$key" to value } }.toMap())
    filesMatching(filePattern) {
        expand(properties)
    }
}

private fun Project.requiredGradleProperty(name: String): String =
    providers.gradleProperty(name).orNull ?: error("$name is missing from gradle.properties")
