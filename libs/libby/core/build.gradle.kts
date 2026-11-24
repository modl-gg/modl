plugins {
    id("modl.libby-module")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val generateLibbyProperties = tasks.register<Copy>("generateLibbyProperties") {
    val libbyVersion = project.version.toString()
    inputs.property("libbyVersion", libbyVersion)
    from("src/main/java-templates")
    into(layout.buildDirectory.dir("generated/sources/templates/java/main"))
    filter { line: String -> line.replace("{{ version }}", libbyVersion) }
}

sourceSets.main {
    java.srcDir(generateLibbyProperties)
}
