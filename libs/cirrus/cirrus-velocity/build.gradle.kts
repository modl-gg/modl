plugins {
    id("modl.cirrus-module")
}

modlJava {
    release = 17
}

dependencies {
    api(project(":libs:cirrus:cirrus-common"))
    compileOnly(libs.velocity.api)
    compileOnly(libs.packetevents.velocity)
}
