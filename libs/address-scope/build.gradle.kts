plugins {
    id("modl.java")
}

group = "gg.modl"
description = "modl address scopes: the one classification of IP address literals shared by the server and the plugin"

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
