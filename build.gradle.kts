plugins {
    base
    id("modl.release")
}

dependencies {
    releaseJars(project(":backend"))
    releaseJars(project(":minecraft:distribution"))
    releaseJars(project(":libs:update-protocol"))
}

tasks.check {
    dependsOn(gradle.includedBuild("build-logic").task(":check"))
}
