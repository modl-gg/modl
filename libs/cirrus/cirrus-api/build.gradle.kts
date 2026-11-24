plugins {
    id("modl.cirrus-module")
}

dependencies {
    compileOnly(libs.packetevents.api)
    compileOnly(libs.adventure.api)

    testImplementation(libs.packetevents.api)
    testImplementation(libs.adventure.api)
}
