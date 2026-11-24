import gg.modl.build.library
import gg.modl.build.versionCatalog

plugins {
    id("modl.java")
}

group = "gg.modl"

val catalog = versionCatalog

dependencies {
    compileOnly(catalog.library("lombok"))
    annotationProcessor(catalog.library("lombok"))
    compileOnly(catalog.library("slf4j-api"))
    compileOnly(catalog.library("gson"))
}
