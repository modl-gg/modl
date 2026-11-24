import gg.modl.build.EmbeddedPayloadIndex
import gg.modl.build.ModlArtifacts
import gg.modl.build.VerifyJarEntries
import gg.modl.build.VerifyVersionsEqual
import gg.modl.build.modlArtifact
import gg.modl.build.resolvedVersionOf
import gg.modl.build.sql.PrepareJooqCodegenDatabase
import gg.modl.build.sql.VerifyMigrationParity
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jooq.meta.jaxb.MatcherTransformType

plugins {
    id("modl.java")
    id("modl.mockito-agent")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.jooq.codegen)
}

group = "gg.modl.backend"
description = "modl server"

modlJava {
    release = 21
    unmarkedPackages.add("gg.modl.backend.database.jooq")
}

base {
    archivesName = "modl-server"
}

val jooqCodegenMigrator = configurations.dependencyScope("jooqCodegenMigrator")
val jooqCodegenMigratorClasspath = configurations.resolvable("jooqCodegenMigratorClasspath") {
    extendsFrom(jooqCodegenMigrator.get())
}

dependencies {
    val springBoot = platform(libs.spring.boot.dependencies)
    implementation(springBoot)
    annotationProcessor(springBoot)

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.websocket)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.mail)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.flyway.mysql)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.sqlite.jdbc)
    runtimeOnly(libs.mysql.connector.j)
    runtimeOnly(libs.mariadb.java.client)
    runtimeOnly(libs.postgresql)
    jooqCodegen(libs.sqlite.jdbc)
    jooqCodegenMigrator(springBoot)
    jooqCodegenMigrator(libs.flyway.core)
    jooqCodegenMigrator(libs.sqlite.jdbc)

    implementation(libs.bucket4j.core)
    implementation(libs.dotenv.java)
    implementation(libs.httpclient5)

    implementation(platform(libs.aws.sdk.bom))
    implementation(libs.aws.sdk.s3) {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    implementation(libs.webauthn.server.core)
    implementation(libs.bcprov)
    compileOnly(libs.jackson2.core)

    implementation(project(":proto"))
    implementation(project(":libs:address-scope"))
    implementation(project(":libs:update-protocol"))
    implementation(libs.protobuf.java.util)
    implementation(libs.protovalidate)

    implementation(libs.caffeine)
    implementation(libs.slugify)

    compileOnly(libs.jakarta.xml.bind.api) {
        because("jOOQ's Settings carry JAXB annotations that javac reads while compiling against them")
    }
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(testFixtures(project(":proto")))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.gson)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.mysql.connector.j)
    testRuntimeOnly(libs.mariadb.java.client)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)

    constraints {
        implementation(libs.guava) {
            because("protovalidate pulls a Guava older than 33.7.2, affected by GHSA-xxph-c9ww-hj94")
        }
    }
}

val repositoryRoot = layout.settingsDirectory
val licenseTexts = repositoryRoot.dir("licenses")

val nativeAccessAttribute = "Enable-Native-Access" to "ALL-UNNAMED"

tasks.withType<Jar>().configureEach {
    manifest {
        attributes("Implementation-Title" to "modl server", "Implementation-Version" to project.version.toString())
        attributes(mapOf(nativeAccessAttribute))
    }
    metaInf {
        from(repositoryRoot.file("LICENSE"), repositoryRoot.file("THIRD_PARTY_NOTICES.md"))
        into("licenses") {
            from(licenseTexts)
        }
    }
}

val migrationRoot = layout.projectDirectory.dir("src/main/resources/db/migration")
val sqliteMigrations = migrationRoot.dir("sqlite")
val jooqCodegenDatabase = layout.buildDirectory.file("jooq/codegen.db")
val jooqGeneratedSources = layout.buildDirectory.dir("generated/sources/jooq/main")
val migrationHistoryTable = "modl_schema_history"
val modlNamePattern = "modl_(.*)"

val prepareJooqCodegenDatabase = tasks.register<PrepareJooqCodegenDatabase>("prepareJooqCodegenDatabase") {
    migrations = sqliteMigrations
    historyTable = migrationHistoryTable
    migratorClasspath.from(jooqCodegenMigratorClasspath)
    database = jooqCodegenDatabase
}

jooq {
    configuration {
        jdbc {
            driver = "org.sqlite.JDBC"
            url = "jdbc:sqlite:" + jooqCodegenDatabase.get().asFile.absolutePath
        }
        generator {
            database {
                name = "org.jooq.meta.sqlite.SQLiteDatabase"
                excludes = "$migrationHistoryTable|sqlite_.*"
                forcedTypes {
                    forcedType {
                        userType = "java.time.Instant"
                        converter = "gg.modl.backend.database.InstantMillisConverter"
                        includeExpression = ".*_at"
                        includeTypes = "BIGINT"
                    }
                    forcedType {
                        name = "BIGINT"
                        includeExpression = "(.*\\.)?modl_(chat_logs|command_logs|audit_logs)\\.id"
                    }
                }
            }
            strategy {
                matchers {
                    tables {
                        table {
                            expression = modlNamePattern
                            tableClass {
                                transform = MatcherTransformType.PASCAL
                                expression = "\$1"
                            }
                            tableIdentifier {
                                transform = MatcherTransformType.UPPER
                                expression = "\$1"
                            }
                            recordClass {
                                transform = MatcherTransformType.PASCAL
                                expression = "\$1_Record"
                            }
                        }
                    }
                    indexes {
                        index {
                            expression = modlNamePattern
                            keyIdentifier {
                                transform = MatcherTransformType.UPPER
                                expression = "\$1"
                            }
                        }
                    }
                    primaryKeys {
                        primaryKey {
                            expression = "pk_$modlNamePattern"
                            keyIdentifier {
                                transform = MatcherTransformType.UPPER
                                expression = "pk_\$1"
                            }
                        }
                    }
                    uniqueKeys {
                        uniqueKey {
                            expression = modlNamePattern
                            keyIdentifier {
                                transform = MatcherTransformType.UPPER
                                expression = "\$1"
                            }
                        }
                    }
                    foreignKeys {
                        foreignKey {
                            expression = modlNamePattern
                            keyIdentifier {
                                transform = MatcherTransformType.UPPER
                                expression = "\$1"
                            }
                        }
                    }
                }
            }
            generate {
                isRecords = true
                isPojos = false
                isDaos = false
                isComments = false
                isGeneratedAnnotation = false
                isImplicitJoinPathsToOne = false
                isImplicitJoinPathsToMany = false
                isImplicitJoinPathsManyToMany = false
            }
            target {
                packageName = "gg.modl.backend.database.jooq"
                directory = jooqGeneratedSources.get().asFile.absolutePath
            }
        }
    }
}

tasks.named("jooqCodegen") {
    dependsOn(prepareJooqCodegenDatabase)
    inputs.dir(sqliteMigrations).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(jooqCodegenDatabase).withPathSensitivity(PathSensitivity.NONE)
    outputs.dir(jooqGeneratedSources)
}

sourceSets.main {
    java.srcDir(tasks.named("jooqCodegen"))
}

val verifyMigrationParity = tasks.register<VerifyMigrationParity>("verifyMigrationParity") {
    dialectFolders.from(listOf("sqlite", "mysql", "postgresql").map { migrationRoot.dir(it) })
    report = layout.buildDirectory.file("verify-migrations/parity.txt")
}

val verifyJooqVersion = tasks.register<VerifyVersionsEqual>("verifyJooqVersion") {
    description = "Fails when the jOOQ code generator and the jOOQ runtime are different versions"
    subject = "org.jooq:jooq on the runtime classpath"
    expectedVersion = libs.versions.jooq
    actualVersion = configurations.runtimeClasspath.flatMap { it.resolvedVersionOf("org.jooq", "jooq") }
    reason = "the generated code and the runtime must come from the same jOOQ release"
}

val requireAllDialects = providers.environmentVariable("MODL_REQUIRE_ALL_DIALECTS").map { it.toBoolean() }.orElse(false)

tasks.withType<Test>().configureEach {
    inputs.property("requireAllDialects", requireAllDialects)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}

val externalHttpTests = mapOf(
    "Mysql" to "mysql-8.4",
    "Mariadb" to "mariadb-10.11",
    "MariadbLatest" to "mariadb-11.4",
    "Postgresql" to "postgresql-15",
    "PostgresqlLatest" to "postgresql-17",
).map { (name, dialect) ->
    tasks.register<Test>("httpTest$name") {
        group = "verification"
        description = "Runs the HTTP integration tests in-process on $dialect"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform {
            includeTags("http")
        }
        environment("MODL_TEST_HTTP_DIALECT", dialect)
        mustRunAfter(tasks.test)
    }
}

if (requireAllDialects.get()) {
    tasks.check {
        dependsOn(externalHttpTests)
    }
}

fun booleanFlag(name: String): Boolean =
    providers.gradleProperty(name).map { it.isEmpty() || it.toBoolean() }.getOrElse(false)

val skipPanel = booleanFlag("modl.skipPanel")
val panelPrebuilt = booleanFlag("modl.panelPrebuilt")

val panelDist = repositoryRoot.dir("panel/dist")
val npmExecutable = if (providers.systemProperty("os.name").get().lowercase().contains("windows")) "npm.cmd" else "npm"
val npmManifests = listOf(
    "package.json",
    "package-lock.json",
    "panel/package.json",
).map { repositoryRoot.file(it) }
val panelCompilerBase = repositoryRoot.file("tsconfig.strict.json")
val panelSources = listOf("panel", "proto/gen/ts").map { module ->
    fileTree(repositoryRoot.dir(module)) {
        exclude("**/node_modules/**", "dist/**")
    }
}

val installPanelDependencies = tasks.register<Exec>("installPanelDependencies") {
    group = "build"
    description = "Installs the npm workspace dependencies the panel build needs"
    workingDir(repositoryRoot)
    commandLine(npmExecutable, "ci")
    inputs.files(npmManifests).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(repositoryRoot.file("node_modules/.package-lock.json"))
}

val gitHeadCommit = providers.exec {
    workingDir(repositoryRoot.asFile)
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

val buildPanel = tasks.register<Exec>("buildPanel") {
    group = "build"
    description = "Builds the panel single-page app into panel/dist"
    dependsOn(installPanelDependencies)
    workingDir(repositoryRoot)
    commandLine(npmExecutable, "run", "build")
    inputs.property("sourceCommit", providers.environmentVariable("MODL_SOURCE_COMMIT").orElse(gitHeadCommit))
    inputs.property("sourceRepository", providers.environmentVariable("MODL_SOURCE_REPOSITORY").orElse(""))
    inputs.files(npmManifests).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(panelSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(panelCompilerBase).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(panelDist)
}

if (!skipPanel) {
    tasks.processResources {
        val panelIndex = panelDist.file("index.html").asFile
        from(if (panelPrebuilt) panelDist else buildPanel) {
            into("static")
            exclude("**/*.map")
        }
        doFirst {
            if (!panelIndex.isFile) {
                throw GradleException("The panel build output is missing ($panelIndex). Build the panel first, or pass -Pmodl.skipPanel to build without it.")
            }
        }
    }
}

val embeddedRuntimeClasspath = configurations.resolvable("embeddedRuntimeClasspath") {
    extendsFrom(configurations.implementation.get(), configurations.runtimeOnly.get())
    attributes.addAllLater(configurations.runtimeClasspath.get().attributes)
    exclude(group = "org.slf4j", module = "jul-to-slf4j")
}

val embeddedPayloadIndex = tasks.register<EmbeddedPayloadIndex>("embeddedPayloadIndex") {
    packedFiles.from(tasks.jar)
    librariesOf(embeddedRuntimeClasspath)
    payloadVersion = project.version.toString()
    outputDirectory = layout.buildDirectory.dir("embedded-index")
}

val packedProjectLibraries = embeddedRuntimeClasspath.map { classpath ->
    classpath.incoming.artifactView {
        componentFilter { it is ProjectComponentIdentifier }
    }.files
}

val embeddedServiceEntries = listOf("META-INF/services/org.apache.juli.logging.Log")
val legalNoticeEntries = listOf("META-INF/LICENSE", "META-INF/THIRD_PARTY_NOTICES.md")

val verifyServerJar = tasks.register<VerifyJarEntries>("verifyServerJar") {
    group = "verification"
    description = "Fails when the plain server jar lacks the legal notices or the service files the embedded server depends on"
    archive = tasks.jar.flatMap { it.archiveFile }
    requiredEntries.addAll(embeddedServiceEntries)
    requiredEntries.addAll(legalNoticeEntries)
    requiredManifestAttributes.put(nativeAccessAttribute.first, nativeAccessAttribute.second)
    bundledFiles.from(fileTree(licenseTexts))
    bundledFilesDirectory = "META-INF/licenses"
    report = layout.buildDirectory.file("verify-jar/server-jar.txt")
}

val verifyServerBootJar = tasks.register<VerifyJarEntries>("verifyServerBootJar") {
    group = "verification"
    description = "Fails when the standalone server jar lacks the legal notices or the native access manifest attribute"
    archive = tasks.bootJar.flatMap { it.archiveFile }
    requiredEntries.addAll(legalNoticeEntries)
    requiredManifestAttributes.put(nativeAccessAttribute.first, nativeAccessAttribute.second)
    bundledFiles.from(fileTree(licenseTexts))
    bundledFilesDirectory = "META-INF/licenses"
    report = layout.buildDirectory.file("verify-jar/server-boot-jar.txt")
}

tasks.jar {
    entryCompression = ZipEntryCompression.STORED
}

tasks.bootJar {
    finalizedBy(verifyServerBootJar)
}

tasks.check {
    dependsOn(verifyServerJar, verifyServerBootJar, verifyMigrationParity, verifyJooqVersion)
}

val generateReleaseBuild = tasks.register<WriteProperties>("generateReleaseBuild") {
    group = "build"
    description = "Writes the release version and the oldest version it upgrades from into the server's resources"
    destinationFile = layout.buildDirectory.file("generated/release-build/build.properties")
    property("version", project.version.toString())
    property("minUpgradeFrom", providers.gradleProperty("modl.minUpgradeFrom"))
}

tasks.processResources {
    from(generateReleaseBuild) {
        into("gg/modl/backend/update")
    }
}

val embeddedPayloadDirectory = layout.buildDirectory.dir("embedded")

val embeddedPayload = tasks.register<Sync>("embeddedPayload") {
    group = "build"
    description = "Assembles the embedded server payload into build/embedded: the server's own jars and the index of " +
        "the libraries the plugin downloads from Maven Central"
    dependsOn(verifyServerJar)
    duplicatesStrategy = DuplicatesStrategy.FAIL
    into(embeddedPayloadDirectory)
    into("lib") {
        from(tasks.jar)
        from(packedProjectLibraries)
    }
    from(embeddedPayloadIndex)
}

val embeddedReleaseZip = tasks.register<Zip>("embeddedReleaseZip") {
    group = "build"
    description = "Packs the embedded server payload as modl-embedded-<version>.zip, which the plugin downloads on its " +
        "first embedded start and installs on Update now"
    archiveFileName = "modl-embedded-${project.version}.zip"
    destinationDirectory = layout.buildDirectory.dir("release")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(embeddedPayload)
}

configurations.consumable("embeddedServerElements") {
    attributes {
        modlArtifact(objects, ModlArtifacts.EMBEDDED_SERVER)
    }
    outgoing.artifact(embeddedReleaseZip)
}

configurations.consumable("releaseJarElements") {
    attributes {
        modlArtifact(objects, ModlArtifacts.RELEASE_JAR)
    }
    outgoing.artifact(tasks.bootJar)
    outgoing.artifact(embeddedReleaseZip)
}
