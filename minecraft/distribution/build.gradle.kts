import gg.modl.build.EmbeddedPayloadPin
import gg.modl.build.ModlArtifacts
import gg.modl.build.PackageRelocation
import gg.modl.build.PluginRelocations
import gg.modl.build.ServerLibraryIndex
import gg.modl.build.VerifyArchiveSize
import gg.modl.build.VerifyPlatformLinkage
import gg.modl.build.WriteEmbeddedPayloadPin
import gg.modl.build.modlArtifact
import gg.modl.build.sha256
import gg.modl.build.toHex
import java.util.zip.ZipFile

plugins {
    id("modl.java")
    alias(libs.plugins.shadow)
}

group = "gg.modl"

java {
    disableAutoTargetJvm()
}

val spigotDescriptor = "plugin.yml"
val bungeeDescriptor = "bungee.yml"
val velocityDescriptor = "velocity-plugin.json"
val pluginDescriptors = listOf(spigotDescriptor, bungeeDescriptor, velocityDescriptor)

val embeddedServerDirectory = "modl-server"
val bundledLibrariesDirectory = "modl-libs"
val bundledLicensesDirectory = "META-INF/licenses"
val bundledLicenses = listOf(
    "cirrus-MIT.txt",
    "libby-MIT.txt",
    "signedvelocity-GPL-3.0.txt",
    "protovalidate-Apache-2.0.txt",
)

val pluginJarBudgetBytes = 15_300_000L

fun serverLibraryFiles(name: String) = configurations.dependencyScope(name).let { scope ->
    scope to configurations.resolvable("${name}Files") {
        extendsFrom(scope.get())
        isTransitive = false
    }
}

val (legacySpigotServerLibraries, legacySpigotServerLibraryFiles) = serverLibraryFiles("legacySpigotServerLibraries")
val (legacyBungeeServerLibraries, legacyBungeeServerLibraryFiles) = serverLibraryFiles("legacyBungeeServerLibraries")
val (legacyVelocityServerLibraries, legacyVelocityServerLibraryFiles) = serverLibraryFiles("legacyVelocityServerLibraries")
val (legacyAdventureLibraries, legacyAdventureLibraryFiles) = serverLibraryFiles("legacyAdventureLibraries")

val adventurePackages = listOf("net.kyori")
val adventurePackagesPacketEventsRelocates = listOf("net.kyori.adventure.text.serializer", "net.kyori.option")

val runtimeLoadedLibraries = configurations.dependencyScope("runtimeLoadedLibraries")
val runtimeLoadedLibraryFiles = configurations.resolvable("runtimeLoadedLibraryFiles") {
    extendsFrom(runtimeLoadedLibraries.get())
    isTransitive = false
    attributes.addAllLater(configurations.runtimeClasspath.get().attributes)
}

val embeddedServer = configurations.dependencyScope("embeddedServer")
val embeddedServerPayload = configurations.resolvable("embeddedServerPayload") {
    extendsFrom(embeddedServer.get())
    attributes {
        modlArtifact(objects, ModlArtifacts.EMBEDDED_SERVER)
    }
}

dependencies {
    implementation(project(":minecraft:core"))
    implementation(project(":minecraft:server-features"))
    implementation(project(":minecraft:platforms:spigot"))
    implementation(project(":minecraft:platforms:spigot-sv"))
    implementation(project(":minecraft:platforms:velocity"))
    implementation(project(":minecraft:platforms:bungee"))

    embeddedServer(project(":backend"))
    runtimeLoadedLibraries(project(":proto"))

    legacySpigotServerLibraries(libs.spigot.legacy.gson)
    legacySpigotServerLibraries(libs.spigot.legacy.guava)
    legacySpigotServerLibraries(libs.spigot.legacy.snakeyaml)
    legacySpigotServerLibraries(libs.spigot.legacy.netty)
    legacyBungeeServerLibraries(libs.bungeecord.legacy.guava)
    legacyVelocityServerLibraries(libs.velocity.legacy.guava)
    legacyAdventureLibraries(libs.adventure.legacy.api)
    legacyAdventureLibraries(libs.adventure.legacy.key)
    legacyAdventureLibraries(libs.examination.legacy.api)
    legacyAdventureLibraries(libs.examination.legacy.string)
}

val embeddedPayloadPin = tasks.register<WriteEmbeddedPayloadPin>("embeddedPayloadPin") {
    group = "build"
    description = "Pins the SHA-256 and size of the embedded server payload the plugin downloads on its first embedded start"
    payload.from(embeddedServerPayload)
    payloadVersion = project.version.toString()
    outputDirectory = layout.buildDirectory.dir("embedded-payload-pin")
}

tasks.shadowJar {
    archiveBaseName.set("modl")
    archiveClassifier.set("")

    PluginRelocations.ALL.forEach { relocation -> relocate(relocation.pattern, relocation.destination) }

    exclude("**/module-info.class")

    from(layout.settingsDirectory.file("LICENSE"), layout.settingsDirectory.file("THIRD_PARTY_NOTICES.md"))
    from(layout.settingsDirectory.dir("licenses")) {
        into(bundledLicensesDirectory)
    }
    from(embeddedPayloadPin) {
        into(embeddedServerDirectory)
    }
    from(runtimeLoadedLibraryFiles) {
        into(bundledLibrariesDirectory)
    }
}

val verifyDistribution = tasks.register("verifyDistribution") {
    group = "verification"
    description = "Verifies the plugin jar's descriptors, embedded server payload pin, bundled libraries and shading boundaries"

    val pluginJar = tasks.shadowJar.flatMap { it.archiveFile }
    val report = layout.buildDirectory.file("verify-distribution/report.txt")
    val pinDirectory = embeddedServerDirectory
    val librariesDirectory = bundledLibrariesDirectory
    val licensesDirectory = bundledLicensesDirectory
    val requiredLicenses = bundledLicenses
    val descriptors = pluginDescriptors
    val spigotDescriptorName = spigotDescriptor
    val expectedVersion = project.version.toString()
    val bundledLibraries: FileCollection = runtimeLoadedLibraryFiles.get()
    val payloadArchives: FileCollection = embeddedServerPayload.get()
    inputs.file(pluginJar).withPropertyName("pluginJar")
    inputs.files(bundledLibraries).withPropertyName("bundledLibraries").withPathSensitivity(PathSensitivity.NAME_ONLY)
    inputs.files(payloadArchives).withPropertyName("payloadArchives").withPathSensitivity(PathSensitivity.NAME_ONLY)
    inputs.property("expectedVersion", expectedVersion)
    outputs.file(report)

    doLast {
        val problems = mutableListOf<String>()
        val jarFile = pluginJar.get().asFile
        val payloadArchive = payloadArchives.singleFile

        ZipFile(payloadArchive).use { payload ->
            val payloadEntries = payload.entries().asSequence().map { it.name }.toSet()
            fun payloadText(entry: String): String? =
                payload.getEntry(entry)?.let { payload.getInputStream(it).bufferedReader().readText() }

            val indexEntry = ServerLibraryIndex.FILE_NAME_IN_PAYLOAD
            val index = payloadText(indexEntry)?.let { content ->
                runCatching { ServerLibraryIndex.parse(content) }
                    .onFailure { problems += "${payloadArchive.name} $indexEntry: ${it.message}" }.getOrNull()
            }
            if (index == null && indexEntry !in payloadEntries) problems += "${payloadArchive.name} has no $indexEntry"
            if (index != null) {
                if (index.packed.isEmpty()) problems += "${payloadArchive.name} $indexEntry packs no server jar"
                if (index.central.isEmpty()) problems += "${payloadArchive.name} $indexEntry lists no libraries to download from Maven Central"
                index.packed.forEach { library ->
                    val entry = "lib/${library.fileName}"
                    when {
                        entry !in payloadEntries -> problems += "${payloadArchive.name} $indexEntry packs ${library.fileName} but $entry is missing"
                        payload.getInputStream(payload.getEntry(entry)).use { it.sha256().toHex() } != library.sha256 ->
                            problems += "${payloadArchive.name} $entry does not match its $indexEntry hash"
                        payload.getEntry(entry).size != library.size -> problems += "${payloadArchive.name} $entry does not match its $indexEntry size"
                    }
                }
                val packedNames = index.packed.map { "lib/${it.fileName}" }.toSet()
                payloadEntries.filter { it.startsWith("lib/") && !it.endsWith("/") && it !in packedNames }
                    .forEach { problems += "${payloadArchive.name} $it is not a packed library in $indexEntry" }
            }
            if (payloadText("version.txt")?.trim() != expectedVersion) problems += "${payloadArchive.name} version.txt is not $expectedVersion"
        }

        ZipFile(jarFile).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()

            fun text(entry: String): String? =
                zip.getEntry(entry)?.let { zip.getInputStream(it).bufferedReader().readText() }

            fun sha256(entry: String): String = zip.getInputStream(zip.getEntry(entry)).use { it.sha256().toHex() }

            descriptors.forEach { descriptor ->
                val content = text(descriptor)
                when {
                    content == null -> problems += "missing plugin descriptor $descriptor"
                    !content.contains("packetevents") -> problems += "$descriptor does not declare PacketEvents"
                }
            }
            if (text(spigotDescriptorName)?.let { Regex("(?m)^depend:").containsMatchIn(it) } == true) {
                problems += "plugin.yml must not hard-depend on other plugins; modl fails closed itself when PacketEvents is missing"
            }

            val pinEntry = "$pinDirectory/${EmbeddedPayloadPin.FILE_NAME}"
            val pin = text(pinEntry)?.let { content ->
                runCatching { EmbeddedPayloadPin.parse(content) }.onFailure { problems += "$pinEntry: ${it.message}" }.getOrNull()
            }
            if (pin == null && pinEntry !in entries) problems += "missing $pinEntry"
            if (pin != null) {
                if (pin.version != expectedVersion) problems += "$pinEntry pins modl ${pin.version}, not $expectedVersion"
                if (pin.fileName != payloadArchive.name) problems += "$pinEntry pins ${pin.fileName}, but the build made ${payloadArchive.name}"
                if (pin.sha256 != payloadArchive.sha256().toHex() || pin.size != payloadArchive.length()) {
                    problems += "$pinEntry does not match the SHA-256 and size of ${payloadArchive.name}"
                }
            }
            entries.filter { it.startsWith("$pinDirectory/") && !it.endsWith("/") && it != pinEntry }
                .forEach { problems += "$it must not ship; the embedded server comes from ${payloadArchive.name}" }

            val bundledNames = bundledLibraries.files.associate { "$librariesDirectory/${it.name}" to it.sha256().toHex() }
            bundledNames.forEach { (entry, hash) ->
                when {
                    entry !in entries -> problems += "the plugin loads $entry at runtime, but the jar does not bundle it"
                    sha256(entry) != hash -> problems += "$entry is not the library the plugin pins for loading at runtime"
                }
            }
            entries.filter { it.startsWith("$librariesDirectory/") && !it.endsWith("/") && it !in bundledNames }
                .forEach { problems += "$it is not a library the plugin loads at runtime" }

            entries.filter { it.lowercase().contains("fabric") }
                .forEach { problems += "Fabric entry $it must not ship" }

            listOf(
                "com/github/retrooper/" to "PacketEvents API classes",
                "io/github/retrooper/" to "PacketEvents platform classes",
                "net/kyori/" to "adventure classes",
                "com/google/protobuf/" to "unrelocated protobuf classes",
                "com/google/gson/" to "unrelocated Gson classes",
                "com/buf/validate/" to "unrelocated buf.validate classes",
                "gg/modl/proto/" to "proto classes (the plugin relocates them from $librariesDirectory at runtime)",
                "gg/modl/libs/" to "relocated library classes (the plugin relocates and loads them at runtime)",
                "gg/modl/backend/" to "embedded server classes (the plugin downloads them in ${payloadArchive.name})",
                "me/frep/" to "Vulcan API stub classes",
                "gg/modl/minecraft/core/query/" to "classes of the removed TCP bridge server",
                "gg/modl/minecraft/core/bridge/" to "classes of the removed TCP bridge protocol",
                "gg/modl/minecraft/features/query/" to "classes of the removed TCP bridge client",
            ).forEach { (prefix, label) ->
                if (entries.any { it.startsWith(prefix) }) problems += "$label ($prefix) must not be shaded"
            }

            listOf(
                "dev/simplix/cirrus/spigot/CirrusSpigot.class",
                "dev/simplix/cirrus/velocity/CirrusVelocity.class",
                "dev/simplix/cirrus/bungee/CirrusBungee.class",
            ).filter { it !in entries }.forEach { problems += "missing shaded cirrus class $it" }

            listOf("LICENSE", "THIRD_PARTY_NOTICES.md", "gg/modl/update/release-keys.txt").filter { it !in entries }
                .forEach { problems += "missing $it" }
            requiredLicenses.map { "$licensesDirectory/$it" }
                .filter { it !in entries }
                .forEach { problems += "missing license notice $it (add it under licenses/ at the repository root)" }

            if (problems.isNotEmpty()) {
                throw GradleException("${jarFile.name} failed verification:\n" + problems.joinToString("\n") { "  $it" })
            }

            report.get().asFile.apply {
                parentFile.mkdirs()
                writeText(
                    "${jarFile.name}: ${entries.size} entries, bundles ${bundledNames.keys.sorted().joinToString(", ")}; pins " +
                        "${payloadArchive.name} (${payloadArchive.length()} bytes)\n"
                )
            }
        }
    }
}

val verifyPluginJarSize = tasks.register<VerifyArchiveSize>("verifyPluginJarSize") {
    group = "verification"
    description = "Fails when the plugin jar grows past its size budget; hosting panels limit plugin uploads"
    archive = tasks.shadowJar.flatMap { it.archiveFile }
    maximumBytes = pluginJarBudgetBytes
    report = layout.buildDirectory.file("verify-plugin-jar-size/report.txt")
}

val verifyPlatformLinkage = tasks.register<VerifyPlatformLinkage>("verifyPlatformLinkage") {
    group = "verification"
    description = "Verifies that every plugin class reachable on a platform loads on its oldest supported Java and " +
        "links against the libraries its oldest supported server bundles"
    pluginJar = tasks.shadowJar.flatMap { it.archiveFile }
    runtimeLoadedLibraries.from(runtimeLoadedLibraryFiles)
    report = layout.buildDirectory.file("verify-platform-linkage/report.txt")
    pluginPackages.add("gg.modl")
    runtimeLoadedPackages.addAll(PluginRelocations.ALL.map(PackageRelocation::destination))
    platform(spigotDescriptor, javaVersion = 8) {
        runtimeGates.add("gg.modl.minecraft.spigot.boot.SignedVelocityBootstrap")
        serverLibraryPackages.addAll("com.google.gson", "com.google.common", "org.yaml.snakeyaml", "io.netty")
        serverLibraryPackages.addAll(adventurePackages)
        unavailableServerPackages.addAll(adventurePackagesPacketEventsRelocates)
        serverLibraries.from(legacySpigotServerLibraryFiles, legacyAdventureLibraryFiles)
    }
    platform(bungeeDescriptor, javaVersion = 8) {
        serverLibraryPackages.add("com.google.common")
        serverLibraryPackages.addAll(adventurePackages)
        unavailableServerPackages.addAll(adventurePackagesPacketEventsRelocates)
        serverLibraries.from(legacyBungeeServerLibraryFiles, legacyAdventureLibraryFiles)
    }
    platform(velocityDescriptor, javaVersion = 17) {
        serverLibraryPackages.add("com.google.common")
        serverLibraries.from(legacyVelocityServerLibraryFiles)
    }
}

tasks.shadowJar {
    finalizedBy(verifyDistribution, verifyPluginJarSize, verifyPlatformLinkage)
}

tasks.check {
    dependsOn(verifyDistribution, verifyPluginJarSize, verifyPlatformLinkage)
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

configurations.consumable("releaseJarElements") {
    attributes {
        modlArtifact(objects, ModlArtifacts.RELEASE_JAR)
    }
    outgoing.artifact(tasks.shadowJar)
}
