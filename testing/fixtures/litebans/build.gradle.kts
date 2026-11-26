plugins {
    id("modl.minecraft-module")
}

dependencies {
    compileOnly(libs.spigot.api)
}

tasks.jar {
    archiveFileName = "litebans-fixture.jar"
}
