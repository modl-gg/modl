plugins {
    id("modl.cirrus-module")
}

dependencies {
    api(project(":libs:cirrus:cirrus-api"))
    compileOnly(libs.packetevents.api)
    compileOnly(libs.adventure.api)

    testImplementation(libs.packetevents.api)
    testImplementation(libs.packetevents.netty.common)
    testRuntimeOnly(libs.spigot.legacy.netty)
    testImplementation(libs.adventure.api)
    testImplementation(libs.adventure.nbt)
    testImplementation(libs.jspecify)
}
