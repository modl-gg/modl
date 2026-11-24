package gg.modl.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.newInstance
import java.util.Base64
import javax.inject.Inject

@CacheableTask
abstract class GenerateLibraryVersions @Inject constructor(private val objects: ObjectFactory) : DefaultTask() {
    @get:Input
    abstract val className: Property<String>

    @get:Input
    abstract val relocations: ListProperty<PackageRelocation>

    @get:Nested
    abstract val libraries: ListProperty<PinnedLibrary>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    fun pin(constantName: String, coordinates: Provider<MavenCoordinates>, artifact: FileCollection, relocated: Boolean) {
        libraries.add(objects.newInstance<PinnedLibrary>().apply {
            this.constantName.set(constantName)
            this.coordinates.set(coordinates)
            this.artifact.from(artifact)
            this.relocated.set(relocated)
        })
    }

    @TaskAction
    fun generate() {
        val qualifiedName = className.get()
        val packageName = qualifiedName.substringBeforeLast('.')
        val simpleName = qualifiedName.substringAfterLast('.')
        val source = outputDirectory.get().asFile.resolve("${qualifiedName.replace('.', '/')}.java")
        source.parentFile.mkdirs()
        source.writeText(
            buildString {
                appendLine("package $packageName;")
                appendLine()
                appendLine("import gg.modl.minecraft.api.LibraryRecord;")
                appendLine("import gg.modl.minecraft.api.LibraryRelocation;")
                appendLine()
                appendLine("import java.util.Arrays;")
                appendLine("import java.util.Collections;")
                appendLine("import java.util.List;")
                appendLine()
                appendLine("final class $simpleName {")
                appendLine()
                appendLine("    private $simpleName() {}")
                appendLine()
                appendLine(relocationsDeclaration())
                appendLine()
                libraries.get().forEach { appendLine(it.declaration()) }
                appendLine("}")
            }
        )
    }

    private fun relocationsDeclaration(): String {
        val entries = relocations.get().joinToString(",\n") { relocation ->
            "            new LibraryRelocation(${javaString(relocation.pattern)}, ${javaString(relocation.destination)})"
        }
        return "    static final List<LibraryRelocation> $RELOCATIONS = Collections.unmodifiableList(Arrays.asList(\n$entries\n    ));"
    }

    abstract class PinnedLibrary {
        @get:Input
        abstract val constantName: Property<String>

        @get:Input
        abstract val coordinates: Property<MavenCoordinates>

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.NAME_ONLY)
        abstract val artifact: ConfigurableFileCollection

        @get:Input
        abstract val relocated: Property<Boolean>

        fun declaration(): String {
            val file = artifact.singleFile
            return libraryDeclaration(
                constantName.get(), coordinates.get(), file.name, Base64.getEncoder().encodeToString(file.sha256()), relocated.get(),
            )
        }
    }

    internal companion object {
        private const val RELOCATIONS = "RELOCATIONS"
        private const val LIBBY_PACKAGE_SEPARATOR = "{}"

        fun libraryDeclaration(
            constantName: String,
            module: MavenCoordinates,
            fileName: String,
            checksum: String,
            relocated: Boolean,
        ): String {
            val plain = "LibraryRecord.of(${javaString(module.group)}, ${javaString(module.artifact)}, " +
                "\"${module.version}\", \"$fileName\", \"$checksum\")"
            val record = module.classifier?.let { "$plain.classifiedAs(\"$it\")" } ?: plain
            val relocatedRecord = if (relocated) "$record.relocatedBy($RELOCATIONS)" else record
            return "    static final LibraryRecord $constantName = $relocatedRecord;"
        }

        private fun javaString(dottedName: String): String = "\"${dottedName.replace(".", LIBBY_PACKAGE_SEPARATOR)}\""
    }
}
