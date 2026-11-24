plugins {
    id("modl.cirrus-module")
}

dependencies {
    api(project(":libs:cirrus:cirrus-common"))
    compileOnly(libs.bungeecord.api)
    compileOnly(libs.packetevents.bungeecord)
}
