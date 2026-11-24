import gg.modl.build.VerifyVersionLine

plugins {
    id("modl.minecraft-module")
    id("modl.mockito-agent")
}

modlJava {
    release = 17
}

dependencies {
    compileOnly(libs.velocity.api)
    annotationProcessor(libs.velocity.api)
    annotationProcessor(libs.lombok)

    implementation(project(":minecraft:core"))

    compileOnly(libs.lamp.common)
    compileOnly(libs.lamp.brigadier)
    compileOnly(libs.lamp.velocity)
    implementation(project(":libs:cirrus:cirrus-velocity"))
    compileOnly(libs.snakeyaml)

    implementation(project(":libs:libby:libby-core"))
    implementation(project(":libs:libby:libby-velocity"))

    compileOnly(libs.packetevents.velocity)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(platform(libs.mockito.bom))
    testImplementation(libs.mockito.core)
    testImplementation(libs.velocity.api)
    testImplementation(libs.lamp.common)
    testImplementation(libs.lamp.velocity)
    testImplementation(libs.lamp.brigadier)
}

val verifyVelocityApiLine = tasks.register<VerifyVersionLine>("verifyVelocityApiLine") {
    description = "Fails unless velocity-api stays on the 3.x line, so one jar keeps resolving on Velocity 3 and 4"
    catalogVersion = "velocity"
    pinnedVersion = libs.versions.velocity
    requiredLine = "3."
    reason = "platforms/velocity must compile against the Velocity 3 API so a single jar resolves on both Velocity 3 " +
        "and Velocity 4 proxies. Velocity 4 kept every API member this module uses, so the 3.x API is the compatible " +
        "floor; raising it drops Velocity 3 support."
}

tasks.jar {
    dependsOn(verifyVelocityApiLine)
    manifest {
        attributes("Automatic-Module-Name" to "gg.modl.minecraft.platform.velocity.plugin")
    }
}

tasks.check {
    dependsOn(verifyVelocityApiLine)
}
