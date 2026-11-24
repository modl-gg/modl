package gg.modl.build

import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.process.CommandLineArgumentProvider

class JavaAgentArgument(@get:Classpath val agent: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-javaagent:${agent.singleFile.absolutePath}")
}
