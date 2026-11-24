plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.errorprone.gradle.plugin)
    implementation(libs.snakeyaml)
    implementation(libs.asm)
    implementation(libs.asm.commons)
    compileOnly(platform(libs.spring.boot.dependencies))
    compileOnly(libs.flyway.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val serverLibraryIndexFixture =
    layout.projectDirectory.file("../minecraft/core/src/test/resources/gg/modl/minecraft/core/embedded/server-library-index.txt")

val embeddedPayloadPinFixture =
    layout.projectDirectory.file("../minecraft/core/src/test/resources/gg/modl/minecraft/core/embedded/payload-pin.txt")

tasks.test {
    useJUnitPlatform()
    inputs.file(serverLibraryIndexFixture).withPropertyName("serverLibraryIndexFixture").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("modl.serverLibraryIndexFixture", serverLibraryIndexFixture.asFile.absolutePath)
    inputs.file(embeddedPayloadPinFixture).withPropertyName("embeddedPayloadPinFixture").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("modl.embeddedPayloadPinFixture", embeddedPayloadPinFixture.asFile.absolutePath)
}
