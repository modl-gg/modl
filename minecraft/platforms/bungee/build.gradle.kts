import gg.modl.build.expandPluginDescriptor
import gg.modl.build.pluginDescriptorProperties

plugins {
    id("modl.minecraft-module")
    id("modl.mockito-agent")
}

dependencies {
    compileOnly(libs.bungeecord.api)

    implementation(project(":minecraft:core"))
    implementation(project(":minecraft:api"))

    compileOnly(libs.lamp.common)
    compileOnly(libs.lamp.bungee)
    implementation(project(":libs:cirrus:cirrus-bungeecord"))

    implementation(project(":libs:libby:libby-core"))
    implementation(project(":libs:libby:libby-bungee"))

    compileOnly(libs.packetevents.bungeecord)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(platform(libs.mockito.bom))
    testImplementation(libs.mockito.core)
    testImplementation(libs.bungeecord.api)
}

tasks.processResources {
    expandPluginDescriptor(pluginDescriptorProperties(), "bungee.yml")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "gg.modl.minecraft.platform.bungee.plugin")
    }
}
