plugins {
    id("modl.java")
}

group = "gg.modl.minecraft.replay"
version = "1.1.7"

dependencies {
    api(project(":libs:replay-format"))
    compileOnly(libs.gson)

    compileOnly(libs.packetevents.api)
    compileOnly(libs.adventure.api)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.gson)
    testImplementation(libs.packetevents.api)
    testImplementation(libs.adventure.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
