plugins {
    id("modl.minecraft-module")
}

dependencies {
    api(project(":minecraft:core"))
    api(project(":minecraft:api"))
    implementation(project(":libs:cirrus:cirrus-api"))
    implementation(project(":libs:replay-format"))
    compileOnly(libs.snakeyaml)
    compileOnly(libs.adventure.api)
    compileOnly(libs.packetevents.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.gson)
    testImplementation(libs.snakeyaml)
    testRuntimeOnly(libs.junit.platform.launcher)
}
