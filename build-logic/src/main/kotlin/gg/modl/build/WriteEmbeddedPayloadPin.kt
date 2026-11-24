package gg.modl.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class WriteEmbeddedPayloadPin : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val payload: ConfigurableFileCollection

    @get:Input
    abstract val payloadVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun write() {
        val archive = payload.files.singleOrNull()
            ?: throw GradleException("Expected exactly one embedded server payload archive, found ${payload.files.map { it.name }}")
        val pin = EmbeddedPayloadPin(payloadVersion.get(), archive.sha256().toHex(), archive.length())
        if (archive.name != pin.fileName) {
            throw GradleException("The embedded server payload is ${archive.name}, but modl ${pin.version} needs ${pin.fileName}")
        }
        outputDirectory.get().asFile.resolve(EmbeddedPayloadPin.FILE_NAME).writeText(pin.write())
    }
}
