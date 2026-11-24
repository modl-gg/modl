package gg.modl.build.linkage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlatformLinkageTest {
    private val serverLibrary = ServerLibraries(
        ClassIndex(
            listOf(
                SyntheticClass("lib/json/Parser").declaring("parse", PARSE_DESCRIPTOR).toClassFile(),
                SyntheticClass("lib/json/SyntaxError").extending("java/lang/RuntimeException").toClassFile(),
                SyntheticClass("lib/json/Element").declaring("<init>", "(I)V").toClassFile(),
                SyntheticClass("lib/json/Primitive").extending("lib/json/Element").declaring("asString", STRING_GETTER).toClassFile(),
            )
        ),
        PackageSet(listOf("lib.json")),
        "json-1.0.jar",
    )

    @Test
    fun newerBytecodeReachableFromTheEntryPointIsReportedWithItsPath() {
        val problems = verify(
            SyntheticClass("app/Entry").calling("app/Helper", "help", "()V").toClassFile(),
            SyntheticClass("app/Helper").calling("app/Modern", "run", "()V").toClassFile(),
            SyntheticClass("app/Modern", javaVersion = 21).toClassFile(),
        ).problems

        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("app/Modern is Java 21 bytecode"), problems.single())
        assertTrue(problems.single().endsWith("app/Entry -> app/Helper -> app/Modern"), problems.single())
    }

    @Test
    fun newerBytecodeBehindARuntimeGateIsAccepted() {
        val result = verify(
            SyntheticClass("app/Entry").calling("app/Gate", "open", "()V").toClassFile(),
            SyntheticClass("app/Gate").loadingByName("app.Integration").toClassFile(),
            SyntheticClass("app/Integration", javaVersion = 21).calling("app/IntegrationHelper", "run", "()V").toClassFile(),
            SyntheticClass("app/IntegrationHelper", javaVersion = 21).toClassFile(),
            runtimeGates = setOf("app/Gate"),
        )

        assertTrue(result.problems.isEmpty(), result.problems.toString())
        assertTrue(result.summary.contains("runtime-gated app/Integration (Java 21, 2 classes)"), result.summary)
    }

    @Test
    fun aGatedIntegrationMustNotReachBytecodeNewerThanItself() {
        val problems = verify(
            SyntheticClass("app/Entry").calling("app/Gate", "open", "()V").toClassFile(),
            SyntheticClass("app/Gate").loadingByName("app.Integration").toClassFile(),
            SyntheticClass("app/Integration", javaVersion = 17).calling("app/Newest", "run", "()V").toClassFile(),
            SyntheticClass("app/Newest", javaVersion = 21).toClassFile(),
            runtimeGates = setOf("app/Gate"),
        ).problems

        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("runtime-gated app/Integration is Java 17"), problems.single())
    }

    @Test
    fun anEntryPointCompiledAboveThePlatformsJavaIsReported() {
        val problems = verify(SyntheticClass("app/Entry", javaVersion = 17).toClassFile()).problems

        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("app/Entry is Java 17 bytecode, but test runs on Java 8"), problems.single())
    }

    @Test
    fun pluginClassesMustBeInTheJarOrLoadedAtRuntime() {
        val problems = verify(
            SyntheticClass("app/Entry")
                .calling("app/Shaded", "run", "()V")
                .calling("app/runtime/Library", "run", "()V")
                .calling("app/Unshaded", "run", "()V")
                .toClassFile(),
            SyntheticClass("app/Shaded").toClassFile(),
        ).problems

        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("uses class app/Unshaded, which is neither in the plugin jar"), problems.single())
    }

    @Test
    fun loadingNewerBytecodeByNameOutsideARuntimeGateIsReported() {
        val problems = verify(
            SyntheticClass("app/Entry").loadingByName("app.Integration").toClassFile(),
            SyntheticClass("app/Integration", javaVersion = 21).toClassFile(),
        ).problems

        assertEquals(1, problems.size, problems.toString())
    }

    @Test
    fun staleRuntimeGatesAreReported() {
        val problems = verify(
            SyntheticClass("app/Entry").calling("app/Gate", "open", "()V").toClassFile(),
            SyntheticClass("app/Gate").toClassFile(),
            SyntheticClass("app/Unreached").loadingByName("app.Entry").toClassFile(),
            runtimeGates = setOf("app/Gate", "app/Unreached", "app/Missing"),
        ).problems

        assertEquals(
            listOf(
                "test: runtime gate app/Gate loads no plugin class by name",
                "test: runtime gate app/Missing is not in the plugin jar",
                "test: runtime gate app/Unreached is not reachable from app/Entry",
            ),
            problems,
        )
    }

    @Test
    fun membersAndClassesTheServerLibraryLacksAreReported() {
        val problems = verify(
            SyntheticClass("app/Entry")
                .calling("lib/json/Parser", "parse", PARSE_DESCRIPTOR)
                .calling("lib/json/Parser", "parseString", PARSE_DESCRIPTOR)
                .calling("lib/json/Policy", "lenient", "()V")
                .calling("other/Library", "anything", "()V")
                .toClassFile(),
        ).problems

        assertEquals(2, problems.size, problems.toString())
        assertTrue(problems.any { it.contains("uses class lib/json/Policy") }, problems.toString())
        assertTrue(problems.any { it.contains("uses lib/json/Parser.parseString$PARSE_DESCRIPTOR") }, problems.toString())
    }

    @Test
    fun classesInPackagesTheServerDoesNotExposeAreReportedAsMissing() {
        val partialServer = ServerLibraries(
            ClassIndex(
                listOf(
                    SyntheticClass("lib/text/Component").declaring("empty", COMPONENT_FACTORY).toClassFile(),
                    SyntheticClass("lib/text/serializer/Serializer").declaring("deserialize", DESERIALIZE_DESCRIPTOR).toClassFile(),
                )
            ).without(PackageSet(listOf("lib.text.serializer"))),
            PackageSet(listOf("lib.text")),
            "text-1.0.jar",
        )

        val problems = verify(
            SyntheticClass("app/Entry")
                .calling("lib/text/Component", "empty", COMPONENT_FACTORY)
                .calling("lib/text/serializer/Serializer", "deserialize", DESERIALIZE_DESCRIPTOR)
                .toClassFile(),
            serverLibraries = partialServer,
        ).problems

        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("uses class lib/text/serializer/Serializer"), problems.single())
    }

    @Test
    fun methodReferencesAreCheckedLikeCalls() {
        val problems = verify(
            SyntheticClass("app/Entry").referencingMethod("lib/json/Parser", "parseString", PARSE_DESCRIPTOR).toClassFile(),
        ).problems

        assertEquals(1, problems.size, problems.toString())
    }

    @Test
    fun inheritedMembersResolveThroughTheLibraryAndTheJdk() {
        val problems = verify(
            SyntheticClass("app/Entry")
                .calling("lib/json/SyntaxError", "getMessage", STRING_GETTER)
                .calling("lib/json/Primitive", "asString", STRING_GETTER)
                .calling("lib/json/Primitive", "hashCode", "()I")
                .toClassFile(),
        ).problems

        assertTrue(problems.isEmpty(), problems.toString())
    }

    @Test
    fun missingMembersAreReportedEvenWhenTheJdkSupertypeLacksThem() {
        val problems = verify(
            SyntheticClass("app/Entry").calling("lib/json/SyntaxError", "isEmpty", "()Z").toClassFile(),
        ).problems

        assertEquals(1, problems.size, problems.toString())
    }

    @Test
    fun constructorsAreNeverInherited() {
        val problems = verify(
            SyntheticClass("app/Entry")
                .calling("lib/json/Element", "<init>", "(I)V")
                .calling("lib/json/Primitive", "<init>", "(I)V")
                .toClassFile(),
        ).problems

        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems.single().contains("lib/json/Primitive.<init>(I)V"), problems.single())
    }

    private fun verify(
        vararg classes: ClassFile,
        runtimeGates: Set<String> = emptySet(),
        serverLibraries: ServerLibraries = serverLibrary,
    ): LinkageResult =
        PlatformLinkage(
            label = "test",
            plugin = ClassIndex(classes.toList()),
            entryPoint = "app/Entry",
            javaVersion = 8,
            runtimeGates = runtimeGates,
            serverLibraries = serverLibraries,
            pluginPackages = PackageSet(listOf("app")),
            runtimeLoadedPackages = PackageSet(listOf("app.runtime")),
        ).verify()

    private companion object {
        const val PARSE_DESCRIPTOR = "(Ljava/lang/String;)Llib/json/Element;"
        const val STRING_GETTER = "()Ljava/lang/String;"
        const val COMPONENT_FACTORY = "()Llib/text/Component;"
        const val DESERIALIZE_DESCRIPTOR = "(Ljava/lang/String;)Llib/text/Component;"
    }
}
