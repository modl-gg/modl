package gg.modl.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.jar.JarFile

abstract class VerifyJarEntries : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val archive: RegularFileProperty

    @get:Input
    abstract val requiredEntries: ListProperty<String>

    @get:Input
    abstract val requiredManifestAttributes: MapProperty<String, String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val bundledFiles: ConfigurableFileCollection

    @get:Input
    @get:Optional
    abstract val bundledFilesDirectory: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val jar = archive.get().asFile
        val expected = requiredEntries.get() + bundledEntries()
        val problems = JarFile(jar).use { zip -> missingEntries(zip, expected) + manifestProblems(zip) }
        if (problems.isNotEmpty()) {
            throw GradleException("${jar.name} is missing ${problems.joinToString(", ")}")
        }
        val attributes = requiredManifestAttributes.get().size
        report.get().asFile.writeText("${jar.name}: ${expected.size} required entries and $attributes manifest attributes present\n")
    }

    private fun missingEntries(zip: JarFile, expected: List<String>): List<String> =
        expected.filter { zip.getEntry(it) == null }

    private fun manifestProblems(zip: JarFile): List<String> {
        val attributes = zip.manifest?.mainAttributes
        return requiredManifestAttributes.get().toSortedMap()
            .filter { (name, value) -> attributes?.getValue(name) != value }
            .map { (name, value) -> "the manifest attribute $name: $value" }
    }

    private fun bundledEntries(): List<String> {
        if (!bundledFilesDirectory.isPresent) {
            return emptyList()
        }
        val names = bundledFiles.files.map { it.name }.sorted()
        if (names.isEmpty()) {
            throw GradleException("No files to bundle under ${bundledFilesDirectory.get()}")
        }
        return names.map { "${bundledFilesDirectory.get()}/$it" }
    }
}
