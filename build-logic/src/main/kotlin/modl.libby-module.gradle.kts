import gg.modl.build.JavaStrictness
import gg.modl.build.ModlJavaExtension
import gg.modl.build.library
import gg.modl.build.versionCatalog

plugins {
    id("modl.java")
}

group = "com.alessiodp.libby"
version = "2.0.0-SNAPSHOT"

the<ModlJavaExtension>().apply {
    usesJdkInternalApis = true
    strictness = JavaStrictness.EXEMPT
}

dependencies {
    compileOnly(versionCatalog.library("jetbrains-annotations"))
}
