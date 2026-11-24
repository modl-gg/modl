import gg.modl.build.JavaStrictness
import gg.modl.build.ModlJavaExtension
import gg.modl.build.library
import gg.modl.build.versionCatalog

plugins {
    id("modl.java")
}

group = "gg.modl.minecraft.cirrus"
version = "4.2.4"

the<ModlJavaExtension>().strictness = JavaStrictness.BUG_CHECKED

val catalog = versionCatalog

dependencies {
    listOf("guava", "gson", "slf4j-api").map(catalog::library).forEach { library ->
        compileOnly(library)
        testImplementation(library)
    }
    val lombok = catalog.library("lombok")
    compileOnly(lombok)
    annotationProcessor(lombok)
    testCompileOnly(lombok)
    testAnnotationProcessor(lombok)

    testImplementation(platform(catalog.library("junit-bom")))
    testImplementation(catalog.library("junit-jupiter"))
    testRuntimeOnly(catalog.library("junit-platform-launcher"))
}
