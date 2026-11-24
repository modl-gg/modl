import gg.modl.build.JavaAgentArgument
import gg.modl.build.library
import gg.modl.build.versionCatalog

plugins {
    id("modl.java")
}

val mockitoAgent = configurations.dependencyScope("mockitoAgent")
val mockitoAgentClasspath = configurations.resolvable("mockitoAgentClasspath") {
    extendsFrom(mockitoAgent.get())
    shouldResolveConsistentlyWith(configurations.testRuntimeClasspath.get())
}

dependencies {
    mockitoAgent(versionCatalog.library("mockito-core")) {
        isTransitive = false
    }
}

tasks.withType<Test>().configureEach {
    jvmArgumentProviders.add(JavaAgentArgument(mockitoAgentClasspath.get()))
}
