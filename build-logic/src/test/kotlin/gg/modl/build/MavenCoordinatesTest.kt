package gg.modl.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class MavenCoordinatesTest {
    @Test
    fun readsThePlainArtifactOfAModule() {
        val coordinates = MavenCoordinates.ofFile("org.springframework", "spring-core", "7.1.0", "spring-core-7.1.0.jar")

        assertNull(coordinates.classifier)
        assertEquals("org.springframework:spring-core:7.1.0", coordinates.toString())
        assertEquals("spring-core-7.1.0.jar", coordinates.fileName)
    }

    @Test
    fun readsTheClassifierFromTheRepositoryFileName() {
        val coordinates = MavenCoordinates.ofFile(
            "io.netty", "netty-transport-native-epoll", "4.2.20.Final", "netty-transport-native-epoll-4.2.20.Final-linux-x86_64.jar",
        )

        assertEquals("linux-x86_64", coordinates.classifier)
        assertEquals("io.netty:netty-transport-native-epoll:4.2.20.Final:linux-x86_64", coordinates.toString())
        assertEquals(coordinates, MavenCoordinates.parse(coordinates.toString()))
    }

    @Test
    fun refusesArtifactsThePluginCannotDownload() {
        val notAJar = assertThrows<IllegalArgumentException> {
            MavenCoordinates.ofFile("org.example", "native", "1.0", "native-1.0.so")
        }
        val renamed = assertThrows<IllegalArgumentException> {
            MavenCoordinates.ofFile("org.example", "library", "1.0", "library-all.jar")
        }

        assertTrue(notAJar.message!!.contains("org.example:native:1.0"), notAJar.message)
        assertTrue(renamed.message!!.contains("library-all.jar"), renamed.message)
    }

    @Test
    fun refusesCoordinatesThatCouldEscapeTheLibraryDirectory() {
        assertThrows<IllegalArgumentException> { MavenCoordinates.parse("org.example:../escape:1.0") }
        assertThrows<IllegalArgumentException> { MavenCoordinates.parse("org.example:library") }
    }
}
