package gg.modl.build

import gg.modl.build.ServerLibraryIndex.CentralLibrary
import gg.modl.build.ServerLibraryIndex.PackedLibrary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class ServerLibraryIndexTest {
    private val serverJar = PackedLibrary("modl-server-3.0.0-plain.jar", "1".repeat(64), 2_251_489)
    private val protoJar = PackedLibrary("proto-3.0.0.jar", "2".repeat(64), 5_633_342)
    private val nativeTransport = CentralLibrary(
        MavenCoordinates("io.netty", "netty-transport-native-epoll", "4.2.20.Final", "linux-x86_64"), "3".repeat(64), 52_424,
    )
    private val springCore = CentralLibrary(MavenCoordinates("org.springframework", "spring-core", "7.1.0"), "4".repeat(64), 2_105_731)

    @Test
    fun writesTheFormatThePluginReads() {
        val fixture = File(System.getProperty(FIXTURE_PROPERTY)).readText()

        assertEquals(fixture, ServerLibraryIndex(listOf(serverJar, protoJar), listOf(nativeTransport, springCore)).write())
    }

    @Test
    fun roundTripsThroughItsTextForm() {
        val index = ServerLibraryIndex(listOf(serverJar, protoJar), listOf(nativeTransport, springCore))

        assertEquals(index, ServerLibraryIndex.parse(index.write()))
    }

    @Test
    fun writesTheSameBytesWhateverOrderTheLibrariesResolvedIn() {
        val resolved = ServerLibraryIndex(listOf(protoJar, serverJar), listOf(springCore, nativeTransport)).write()
        val reversed = ServerLibraryIndex(listOf(serverJar, protoJar), listOf(nativeTransport, springCore)).write()

        assertEquals(reversed, resolved)
        assertTrue(resolved.startsWith(ServerLibraryIndex.HEADER + "\n"), resolved)
        assertTrue(resolved.endsWith("\n") && !resolved.contains("\r"), resolved)
    }

    @Test
    fun rejectsTwoLibrariesThatWouldShareAFile() {
        val clash = CentralLibrary(MavenCoordinates("org.other", "spring-core", "7.1.0"), "5".repeat(64), 10)

        val failure = assertThrows<IllegalArgumentException> { ServerLibraryIndex(emptyList(), listOf(springCore, clash)) }

        assertTrue(failure.message!!.contains("spring-core-7.1.0.jar"), failure.message)
    }

    @Test
    fun rejectsAnIndexOfAnotherFormat() {
        assertThrows<IllegalArgumentException> { ServerLibraryIndex.parse("${"a".repeat(64)} spring-core-7.1.0.jar\n") }
        assertThrows<IllegalArgumentException> { ServerLibraryIndex.parse("${ServerLibraryIndex.HEADER}\nmirror ${"a".repeat(64)} 1 x.jar\n") }
    }

    private companion object {
        const val FIXTURE_PROPERTY = "modl.serverLibraryIndexFixture"
    }
}
