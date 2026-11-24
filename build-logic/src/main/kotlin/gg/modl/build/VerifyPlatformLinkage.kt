package gg.modl.build

import gg.modl.build.linkage.ClassIndex
import gg.modl.build.linkage.LinkageResult
import gg.modl.build.linkage.PackageSet
import gg.modl.build.linkage.PlatformLinkage
import gg.modl.build.linkage.ServerLibraries
import groovy.json.JsonSlurper
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.newInstance
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject

@CacheableTask
abstract class VerifyPlatformLinkage @Inject constructor(private val objects: ObjectFactory) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val pluginJar: RegularFileProperty

    @get:Classpath
    abstract val runtimeLoadedLibraries: ConfigurableFileCollection

    @get:Input
    abstract val pluginPackages: SetProperty<String>

    @get:Input
    abstract val runtimeLoadedPackages: SetProperty<String>

    @get:Nested
    abstract val platforms: ListProperty<Platform>

    @get:OutputFile
    abstract val report: RegularFileProperty

    fun platform(descriptor: String, javaVersion: Int, configure: Action<in Platform> = Action {}) {
        platforms.add(objects.newInstance<Platform>().apply {
            this.descriptor.set(descriptor)
            this.javaVersion.set(javaVersion)
            configure.execute(this)
        })
    }

    @TaskAction
    fun verify() {
        val jar = pluginJar.get().asFile
        val plugin = ClassIndex.read(listOf(jar) + runtimeLoadedLibraries.files.sortedBy(File::getName))
        val packages = PackageSet(pluginPackages.get())
        val runtimeLoaded = PackageSet(runtimeLoadedPackages.get())
        val results = platforms.get().map { it.linkage(jar, plugin, packages, runtimeLoaded).verify() }
        val problems = results.flatMap(LinkageResult::problems)
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${jar.name} contains classes that cannot load or link on a platform it supports:\n" +
                    problems.joinToString("\n") { "  $it" }
            )
        }
        report.get().asFile.writeText(results.joinToString("") { "${it.summary}\n" })
    }

    abstract class Platform {
        @get:Input
        abstract val descriptor: Property<String>

        @get:Input
        abstract val javaVersion: Property<Int>

        @get:Input
        abstract val runtimeGates: SetProperty<String>

        @get:Input
        abstract val serverLibraryPackages: SetProperty<String>

        @get:Input
        abstract val unavailableServerPackages: SetProperty<String>

        @get:Classpath
        abstract val serverLibraries: ConfigurableFileCollection

        init {
            runtimeGates.convention(emptySet())
            serverLibraryPackages.convention(emptySet())
            unavailableServerPackages.convention(emptySet())
        }

        internal fun linkage(jar: File, plugin: ClassIndex, pluginPackages: PackageSet, runtimeLoaded: PackageSet): PlatformLinkage {
            val libraryFiles = serverLibraries.files.sortedBy { it.name }
            val unavailablePackages = unavailableServerPackages.get().sorted()
            return PlatformLinkage(
                label = descriptor.get(),
                plugin = plugin,
                entryPoint = internalName(mainClassOf(jar)),
                javaVersion = javaVersion.get(),
                runtimeGates = runtimeGates.get().map(::internalName).toSet(),
                serverLibraries = ServerLibraries(
                    ClassIndex.read(libraryFiles).without(PackageSet(unavailablePackages)),
                    PackageSet(serverLibraryPackages.get()),
                    describe(libraryFiles, unavailablePackages),
                ),
                pluginPackages = pluginPackages,
                runtimeLoadedPackages = runtimeLoaded,
            )
        }

        private fun mainClassOf(jar: File): String {
            val descriptorName = descriptor.get()
            val content = ZipFile(jar).use { zip ->
                val entry = zip.getEntry(descriptorName) ?: throw GradleException("${jar.name} has no $descriptorName")
                zip.getInputStream(entry).bufferedReader().readText()
            }
            val root = if (descriptorName.endsWith(JSON_SUFFIX)) JsonSlurper().parseText(content) else Yaml().load<Any?>(content)
            return (root as? Map<*, *>)?.get(MAIN_CLASS_KEY) as? String
                ?: throw GradleException("$descriptorName in ${jar.name} does not name a $MAIN_CLASS_KEY class")
        }

        private fun internalName(binaryName: String): String = binaryName.replace('.', '/')

        private fun describe(libraryFiles: List<File>, unavailablePackages: List<String>): String {
            val libraries = libraryFiles.joinToString(", ") { it.name }.ifEmpty { NO_SERVER_LIBRARIES }
            if (unavailablePackages.isEmpty()) return libraries
            return "$libraries, without ${unavailablePackages.joinToString(", ")}"
        }

        private companion object {
            const val MAIN_CLASS_KEY = "main"
            const val JSON_SUFFIX = ".json"
            const val NO_SERVER_LIBRARIES = "none declared"
        }
    }
}
