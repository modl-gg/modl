package gg.modl.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.yaml.snakeyaml.Yaml
import java.io.File

@CacheableTask
abstract class VerifyLocaleParity : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localeDirectory: DirectoryProperty

    @get:Input
    abstract val referenceLocale: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val localeFiles = localeDirectory.get().asFile
        val reference = referenceLocale.get()
        val referenceKeys = leafKeysOf(localeFiles.resolve(reference))
        val problems = StringBuilder()

        localeFiles.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".yml") && it.name != reference }
            .sortedBy { it.name }
            .forEach { file ->
                val keys = leafKeysOf(file)
                val missing = referenceKeys - keys
                val extra = keys - referenceKeys
                if (missing.isEmpty() && extra.isEmpty()) return@forEach

                problems.appendLine("  ${file.name}:")
                missing.sorted().forEach { problems.appendLine("    missing key: $it") }
                extra.sorted().forEach { problems.appendLine("    unknown key: $it") }
            }

        if (problems.isNotEmpty()) {
            throw GradleException("Locale files are not in parity with $reference:\n$problems")
        }
        report.get().asFile.writeText("${referenceKeys.size} keys verified against $reference\n")
    }

    private fun leafKeysOf(file: File): Set<String> {
        val root = file.inputStream().use { Yaml().load<Any?>(it) }
        val keys = linkedSetOf<String>()
        collectLeafKeys(root, "", keys)
        return keys
    }

    private fun collectLeafKeys(value: Any?, path: String, sink: MutableSet<String>) {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) ->
                collectLeafKeys(child, if (path.isEmpty()) "$key" else "$path.$key", sink)
            }
            is List<*> -> value.forEachIndexed { index, child ->
                collectLeafKeys(child, "$path[$index]", sink)
            }
            else -> if (path.isNotEmpty()) sink.add(path)
        }
    }
}
