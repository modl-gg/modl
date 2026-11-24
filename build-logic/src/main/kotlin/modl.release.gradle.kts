import gg.modl.build.ModlArtifacts
import gg.modl.build.modlArtifact

val releaseJars = configurations.dependencyScope("releaseJars")

val releaseJarFiles = configurations.resolvable("releaseJarFiles") {
    extendsFrom(releaseJars.get())
    attributes {
        modlArtifact(objects, ModlArtifacts.RELEASE_JAR)
    }
}

tasks.register<Sync>("dist") {
    group = "distribution"
    description = "Builds the standalone modl server jar, the embedded server patch and the modl plugin jar into dist/ (or -Pmodl.distDir)"
    from(releaseJarFiles)
    into(providers.gradleProperty("modl.distDir").map { layout.projectDirectory.dir(it) }.orElse(layout.projectDirectory.dir("dist")))
}
