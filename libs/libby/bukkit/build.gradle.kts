plugins {
    id("modl.libby-module")
}

dependencies {
    api(project(":libs:libby:libby-core"))
    compileOnly(libs.spigot.api)
}
