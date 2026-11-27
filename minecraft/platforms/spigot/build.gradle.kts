import gg.modl.build.expandPluginDescriptor
import gg.modl.build.pluginDescriptorProperties

plugins {
    id("modl.minecraft-module")
    id("modl.mockito-agent")
}

val grimApiJavaVersion = 17

dependencies {
    compileOnly(libs.spigot.api)

    compileOnly(libs.grim.api) {
        attributes {
            attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, grimApiJavaVersion)
        }
    }
    compileOnly(libs.polar.api)
    compileOnly(libs.intave.access)
    compileOnly(project(":minecraft:platforms:vulcan-api-stub"))

    compileOnly(libs.adventure.api)

    implementation(project(":minecraft:core"))
    implementation(project(":minecraft:api"))
    implementation(project(":minecraft:server-features"))

    compileOnly(libs.lamp.common)
    compileOnly(libs.lamp.bukkit)
    compileOnly(libs.lamp.brigadier)
    compileOnly(libs.brigadier)

    implementation(project(":libs:cirrus:cirrus-spigot"))

    implementation(project(":libs:libby:libby-core"))
    implementation(project(":libs:libby:libby-bukkit"))

    compileOnly(libs.packetevents.spigot)

    implementation(project(":libs:replay-format"))
    implementation(project(":libs:replay-recording"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(platform(libs.mockito.bom))
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.lamp.common)
    testImplementation(libs.lamp.bukkit)
    testImplementation(libs.spigot.api)
    testImplementation(libs.packetevents.spigot)
    testImplementation(libs.adventure.text.serializer.legacy)
    testRuntimeOnly(project(":minecraft:platforms:spigot-sv"))
}

tasks.processResources {
    expandPluginDescriptor(pluginDescriptorProperties(), "plugin.yml")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "gg.modl.minecraft.platform.spigot.plugin")
    }
}
