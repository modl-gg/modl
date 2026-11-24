package gg.modl.build.linkage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ClassGraphTest {
    private val classes = ClassIndex(
        listOf(
            SyntheticClass("app/Entry").calling("app/Helper", "help", "()V").calling("app/Gate", "open", "()V").toClassFile(),
            SyntheticClass("app/Helper").loadingByName("app.Reflected").toClassFile(),
            SyntheticClass("app/Reflected").toClassFile(),
            SyntheticClass("app/Gate").loadingByName("app.Integration").loadingByName("not.in.TheJar").toClassFile(),
            SyntheticClass("app/Integration", javaVersion = 21).toClassFile(),
            SyntheticClass("app/Unused").toClassFile(),
        )
    )
    private val graph = ClassGraph(classes, setOf("app/Gate"))

    @Test
    fun followsTypeReferencesAndClassesLoadedByName() {
        val reachable = graph.reachableFrom("app/Entry")

        assertEquals(setOf("app/Entry", "app/Helper", "app/Reflected", "app/Gate"), reachable.classes)
        assertEquals(listOf("app/Entry", "app/Helper", "app/Reflected"), reachable.pathTo("app/Reflected"))
    }

    @Test
    fun classesLoadedByNameBehindARuntimeGateAreGatedEntryPoints() {
        assertFalse("app/Integration" in graph.reachableFrom("app/Entry").classes)
        assertEquals(setOf("app/Integration"), graph.gatedEntryPointsOf("app/Gate"))
    }
}
