import gg.modl.build.JavaStrictness
import gg.modl.build.LintArguments
import gg.modl.build.ModlJavaExtension
import gg.modl.build.library
import gg.modl.build.versionCatalog
import net.ltgt.gradle.errorprone.CheckSeverity
import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
    id("net.ltgt.errorprone")
}

val modlJava = extensions.create<ModlJavaExtension>("modlJava").apply {
    release.convention(ModlJavaExtension.MINECRAFT_RUNTIME_RELEASE)
    usesJdkInternalApis.convention(false)
    strictness.convention(JavaStrictness.NULL_CHECKED)
    unmarkedPackages.convention(emptySet())
}

version = providers.gradleProperty("modl.version").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(ModlJavaExtension.TOOLCHAIN_VERSION)
    }
}

val catalog = versionCatalog

dependencies {
    errorprone(catalog.library("errorprone-core"))
    errorprone(catalog.library("nullaway"))
    compileOnlyApi(catalog.library("jspecify"))
    testCompileOnly(catalog.library("jspecify"))
}

val nullChecked = modlJava.strictness.map { it.nullChecked }

sourceSets.configureEach {
    val nullCheckedSourceSet = name != SourceSet.TEST_SOURCE_SET_NAME
    tasks.named<JavaCompile>(compileJavaTaskName) {
        options.errorprone {
            compilingTestOnlyCode = !nullCheckedSourceSet
            checks.put("NullAway", nullChecked.map { if (it && nullCheckedSourceSet) CheckSeverity.ERROR else CheckSeverity.OFF })
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    options.compilerArgumentProviders.add(LintArguments(modlJava.strictness))
    options.errorprone {
        enabled = modlJava.strictness.map { it.bugChecked }
        disableWarningsInGeneratedCode = true
        excludedPaths = ".*/build/generated/.*"
        checkOptions.put("NullAway:AnnotatedPackages", ModlJavaExtension.NULL_MARKED_PACKAGES)
        checkOptions.putAll(modlJava.unmarkedPackages.map { packages ->
            if (packages.isEmpty()) emptyMap() else mapOf("NullAway:UnannotatedSubPackages" to packages.sorted().joinToString(","))
        })
        checkOptions.put("NullAway:JSpecifyMode", "true")
        checkOptions.put("NullAway:ExternalInitAnnotations", "lombok.NoArgsConstructor")
        disable(*ModlJavaExtension.DISABLED_BUG_CHECKS.toTypedArray())
    }
}

tasks.named<JavaCompile>("compileJava") {
    if (modlJava.usesJdkInternalApis.get()) {
        val bytecodeLevel = JavaVersion.toVersion(modlJava.release.get()).toString()
        sourceCompatibility = bytecodeLevel
        targetCompatibility = bytecodeLevel
    } else {
        options.release = modlJava.release
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
