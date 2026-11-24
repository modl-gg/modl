import gg.modl.build.GenerateLibraryVersions
import gg.modl.build.MavenCoordinates
import gg.modl.build.PluginRelocations
import gg.modl.build.VerifyLocaleParity
import gg.modl.build.expandPluginDescriptor
import gg.modl.build.pluginDescriptorProperties
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins {
    id("modl.minecraft-module")
    id("modl.mockito-agent")
}

val protoLibrary = configurations.dependencyScope("protoLibrary")
val protoLibraryFile = configurations.resolvable("protoLibraryFile") {
    extendsFrom(protoLibrary.get())
    isTransitive = false
    attributes.addAllLater(configurations.runtimeClasspath.get().attributes)
}

dependencies {
    api(project(":minecraft:api"))
    implementation(project(":libs:address-scope"))
    implementation(project(":libs:update-protocol"))
    compileOnly(project(":proto")) { isTransitive = false }
    protoLibrary(project(":proto"))
    implementation(project(":libs:cirrus:cirrus-api"))
    compileOnly(project(":libs:libby:libby-core"))
    compileOnly(libs.protobuf.java)
    compileOnly(libs.guava)
    compileOnly(libs.lamp.common)
    compileOnly(libs.snakeyaml)
    compileOnly(libs.java.websocket)
    compileOnly(libs.adventure.api)

    testImplementation(libs.lamp.common)
    testImplementation(libs.lamp.brigadier)
    testImplementation(libs.brigadier)
    testImplementation(project(":proto"))
    testImplementation(testFixtures(project(":proto")))
    testImplementation(project(":libs:libby:libby-core"))
    testImplementation(libs.guava)
    testImplementation(libs.packetevents.api)
    testImplementation(libs.java.websocket)
    testImplementation(libs.adventure.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(platform(libs.mockito.bom))
    testImplementation(libs.mockito.core)
    testImplementation(libs.gson)
    testImplementation(libs.snakeyaml)
    testImplementation(variantOf(libs.import.hsqldb) { classifier("jdk8") })
    testImplementation(libs.import.h2.legacy)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val generateTemplates = tasks.register<Copy>("generateTemplates") {
    from("src/main/java-templates")
    into(layout.buildDirectory.dir("generated/sources/templates/java/main"))
    expandPluginDescriptor(pluginDescriptorProperties(), "**/*")
    filteringCharset = "UTF-8"
}

val runtimeLibraries = mapOf<String, Pair<Provider<MinimalExternalModuleDependency>, String?>>(
    "SNAKEYAML" to (libs.snakeyaml to null),
    "GSON" to (libs.gson to null),
    "JAVA_WEBSOCKET" to (libs.java.websocket to null),
    "LAMP_COMMON" to (libs.lamp.common to null),
    "LAMP_BRIGADIER" to (libs.lamp.brigadier to null),
    "LAMP_BUKKIT" to (libs.lamp.bukkit to null),
    "LAMP_VELOCITY" to (libs.lamp.velocity to null),
    "LAMP_BUNGEE" to (libs.lamp.bungee to null),
    "SLF4J_API" to (libs.slf4j.api to null),
    "SLF4J_SIMPLE" to (libs.slf4j.simple to null),
    "PROTOBUF_JAVA" to (libs.protobuf.java.asProvider() to null),
    "IMPORT_HSQLDB" to (libs.import.hsqldb to "jdk8"),
    "IMPORT_H2_LEGACY" to (libs.import.h2.legacy to null),
    "IMPORT_SQLITE" to (libs.sqlite.jdbc to null),
    "IMPORT_MARIADB" to (libs.import.mariadb to null),
    "IMPORT_POSTGRESQL" to (libs.import.postgresql to null),
)
val bundledProtoLibrary = "PROTO"

val unpinnedRelocationProviders = PluginRelocations.RELOCATED_LIBRARIES - runtimeLibraries.keys - bundledProtoLibrary
check(unpinnedRelocationProviders.isEmpty()) {
    "PluginRelocations relocates packages provided by runtime libraries that core does not pin: $unpinnedRelocationProviders"
}

val generateLibraryVersions = tasks.register<GenerateLibraryVersions>("generateLibraryVersions") {
    className = "gg.modl.minecraft.core.LibraryVersions"
    outputDirectory = layout.buildDirectory.dir("generated/sources/libraryVersions/java/main")
    relocations = PluginRelocations.ALL
    runtimeLibraries.forEach { (constantName, pinned) ->
        val (library, classifier) = pinned
        val dependency = library.get()
        val coordinates = MavenCoordinates(
            dependency.module.group, dependency.module.name, dependency.versionConstraint.requiredVersion, classifier,
        )
        val artifact = configurations.detachedConfiguration(project.dependencies.create(coordinates.toString())).apply {
            isTransitive = false
        }
        pin(constantName, provider { coordinates }, artifact, PluginRelocations.relocates(constantName))
    }
    val protoCoordinates = protoLibraryFile.flatMap { it.incoming.resolutionResult.rootComponent }.map { root ->
        val proto = root.dependencies.filterIsInstance<ResolvedDependencyResult>().single().selected.moduleVersion
            ?: throw GradleException("The proto library has no module version")
        MavenCoordinates(proto.group, proto.name, proto.version)
    }
    pin(bundledProtoLibrary, protoCoordinates, protoLibraryFile.get(), PluginRelocations.relocates(bundledProtoLibrary))
}

sourceSets.main {
    java.srcDir(generateTemplates)
    java.srcDir(generateLibraryVersions)
}

val verifyLocaleParity = tasks.register<VerifyLocaleParity>("verifyLocaleParity") {
    group = "verification"
    description = "Fails when a locale file has keys missing from, or absent from, en_US.yml."
    localeDirectory = layout.projectDirectory.dir("src/main/resources/locale")
    referenceLocale = "en_US.yml"
    report = layout.buildDirectory.file("locale-parity/report.txt")
}

tasks.processResources {
    dependsOn(verifyLocaleParity)
}

tasks.check {
    dependsOn(verifyLocaleParity)
}
