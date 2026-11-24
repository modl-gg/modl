package gg.modl.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.Serializable

abstract class EmbeddedPayloadIndex : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val packedFiles: ConfigurableFileCollection

    @get:Input
    abstract val resolvedLibraries: ListProperty<ResolvedLibrary>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val resolvedFiles: ConfigurableFileCollection

    @get:Input
    abstract val payloadVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    fun librariesOf(classpath: Provider<out Configuration>) {
        val artifacts = classpath.flatMap { it.incoming.artifacts.resolvedArtifacts }
        resolvedLibraries.addAll(artifacts.map { results -> results.map(ResolvedLibrary::of) })
        resolvedFiles.from(artifacts.map { results -> results.map(ResolvedArtifactResult::getFile) })
    }

    @TaskAction
    fun write() {
        val resolved = resolvedFiles.files.associateBy(File::getName)
        val libraries = resolvedLibraries.get()
        val index = ServerLibraryIndex(
            packed = (packedFiles.files + libraries.filter(ResolvedLibrary::isPacked).map { resolved.getValue(it.fileName) })
                .map { ServerLibraryIndex.PackedLibrary(it.name, it.sha256().toHex(), it.length()) },
            central = libraries.mapNotNull { library ->
                library.downloadFrom?.let { coordinates ->
                    resolved.getValue(library.fileName).takeUnless(JarContents::isMetadataOnly)?.let { file ->
                        ServerLibraryIndex.CentralLibrary(coordinates, file.sha256().toHex(), file.length())
                    }
                }
            },
        )
        val directory = outputDirectory.get().asFile
        directory.resolve(ServerLibraryIndex.FILE_NAME_IN_PAYLOAD).writeText(index.write())
        directory.resolve(VERSION_FILE).writeText("${payloadVersion.get()}\n")
    }

    data class ResolvedLibrary(val fileName: String, val downloadFrom: MavenCoordinates?) : Serializable {
        val isPacked: Boolean
            get() = downloadFrom == null

        companion object {
            fun of(result: ResolvedArtifactResult): ResolvedLibrary {
                val fileName = result.file.name
                return when (val component = result.id.componentIdentifier) {
                    is ModuleComponentIdentifier -> ResolvedLibrary(
                        fileName,
                        MavenCoordinates.ofFile(component.group, component.module, component.version, fileName),
                    )
                    is ProjectComponentIdentifier -> ResolvedLibrary(fileName, null)
                    else -> throw GradleException(
                        "${result.id.displayName} is neither a Maven module nor a project of this build, so the " +
                            "plugin can neither download it from Maven Central nor pack it"
                    )
                }
            }
        }
    }

    private companion object {
        const val VERSION_FILE = "version.txt"
    }
}
