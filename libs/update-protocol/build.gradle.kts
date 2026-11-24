import gg.modl.build.ModlArtifacts
import gg.modl.build.modlArtifact

plugins {
    id("modl.java")
}

group = "gg.modl"
description = "modl update protocol: versions, compatibility, signed releases and update state files"

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.gson)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val releaseKeysFile = providers.gradleProperty("modl.releaseKeysFile")

tasks.processResources {
    inputs.property("releaseKeysFile", releaseKeysFile.orElse(""))
    if (releaseKeysFile.isPresent) {
        val override = file(releaseKeysFile.get())
        exclude("gg/modl/update/release-keys.txt")
        from(override) {
            into("gg/modl/update")
            rename { "release-keys.txt" }
        }
    }
}

val releaseContractFile = layout.buildDirectory.file("release/release-contract.json")

val releaseContract = tasks.register<JavaExec>("releaseContract") {
    group = "build"
    description = "Writes release-contract.json, the contract numbers the update hosts check, for the release manifest"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "gg.modl.update.ReleaseContractFile"
    val output = releaseContractFile
    outputs.file(output)
    argumentProviders.add(CommandLineArgumentProvider { listOf(output.get().asFile.absolutePath) })
}

configurations.consumable("releaseJarElements") {
    attributes {
        modlArtifact(objects, ModlArtifacts.RELEASE_JAR)
    }
    outgoing.artifact(releaseContractFile) {
        builtBy(releaseContract)
    }
}
