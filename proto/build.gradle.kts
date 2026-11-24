import net.ltgt.gradle.errorprone.errorprone


plugins {
    id("modl.java")
    `java-test-fixtures`
}

group = "gg.modl"

sourceSets {
    main {
        java {
            srcDir("gen/java")
        }
    }
}

dependencies {
    api(libs.protobuf.java)
    testFixturesApi(libs.protobuf.java)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.compileJava {
    options.errorprone.enabled = false
}

tasks.register<JavaExec>("regenerateNetworkFixtures") {
    group = "verification"
    description = "Rewrites the shared network envelope fixtures from NetworkEnvelopeFixtureMessages after a deliberate contract change"
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass = "gg.modl.proto.fixtures.NetworkEnvelopeFixtureGenerator"
    args(layout.projectDirectory.dir("src/testFixtures/resources/network").asFile.absolutePath)
}

tasks.jar {
    entryCompression = ZipEntryCompression.STORED
    from(layout.settingsDirectory.file("licenses/protovalidate-Apache-2.0.txt")) {
        into("META-INF/licenses")
    }
}
