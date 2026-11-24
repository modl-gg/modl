plugins {
    id("modl.libby-module")
}

modlJava {
    release = 17
}

dependencies {
    api(project(":libs:libby:libby-core"))
    compileOnly(libs.velocity.api)
}
