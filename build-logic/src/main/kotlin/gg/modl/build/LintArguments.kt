package gg.modl.build

import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.process.CommandLineArgumentProvider

class LintArguments(@get:Input val strictness: Provider<JavaStrictness>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = if (strictness.get().bugChecked) STRICT else LENIENT

    private companion object {
        val LENIENT = listOf("-Xlint:-options")
        val STRICT = listOf(
            "-Xlint:all",
            "-Xlint:-options",
            "-Xlint:-processing",
            "-Xlint:-classfile",
            "-Xlint:-serial",
            "-Xlint:-try",
            "-Xlint:-this-escape",
            "-Werror",
            "-Xmaxerrs",
            "100000",
            "-Xmaxwarns",
            "100000",
        )
    }
}
