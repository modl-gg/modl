plugins {
    id("modl.java")
}

group = "gg.modl.minecraft.replay"

dependencies {
    api(project(":libs:replay-format"))
    compileOnly(project(":proto")) { isTransitive = false }
    compileOnly(libs.protobuf.java)
    compileOnly(libs.gson)
    compileOnly(libs.packetevents.api)
    compileOnly(libs.adventure.api)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(project(":proto"))
    testImplementation(libs.gson)
    testImplementation(libs.packetevents.api)
    testImplementation(libs.packetevents.netty.common)
    testImplementation(libs.spigot.legacy.netty)
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.nbt)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
