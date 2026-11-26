import gg.modl.build.VerifyVersionLine

plugins {
    id("modl.minecraft-module")
}

modlJava {
    release = 17
}

dependencies {
    compileOnly(libs.paper.api)
    compileOnly(libs.packetevents.spigot)
    implementation(project(":minecraft:core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.paper.api)
    testImplementation(libs.packetevents.spigot)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val verifyPaperApiLine = tasks.register<VerifyVersionLine>("verifyPaperApiLine") {
    description = "Fails unless paper-api stays on Paper 1.19.1, the oldest Paper the embedded SignedVelocity listeners support"
    catalogVersion = "paper"
    pinnedVersion = libs.versions.paper
    requiredLine = "1.19.1-"
    reason = "platforms/spigot-sv must compile against the Paper 1.19.1 API, the first with the signed chat events " +
        "(AsyncChatDecorateEvent) its listeners use, so every member they call exists on each Paper they load on. The " +
        "bootstrap gate in platforms/spigot (SignedVelocitySupport) names the same floor; raise both together, and only " +
        "to drop support for older Paper servers."
}

tasks.compileJava {
    dependsOn(verifyPaperApiLine)
}

tasks.check {
    dependsOn(verifyPaperApiLine)
}
