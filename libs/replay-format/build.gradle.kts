plugins {
    id("modl.java")
}

group = "gg.modl.minecraft.replay"

dependencies {
    compileOnly(project(":proto")) { isTransitive = false }
    compileOnly(libs.protobuf.java)

    testImplementation(project(":proto"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
