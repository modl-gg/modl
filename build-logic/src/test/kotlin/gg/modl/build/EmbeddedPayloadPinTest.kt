package gg.modl.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class EmbeddedPayloadPinTest {
    private val pin = EmbeddedPayloadPin("3.0.0", "5".repeat(64), 12_345_678)

    @Test
    fun writesTheFormatThePluginReads() {
        assertEquals(File(System.getProperty(FIXTURE_PROPERTY)).readText(), pin.write())
    }

    @Test
    fun roundTripsThroughItsTextForm() {
        assertEquals(pin, EmbeddedPayloadPin.parse(pin.write()))
    }

    @Test
    fun namesTheReleaseArchiveOfItsVersion() {
        assertEquals("modl-embedded-3.0.0.zip", pin.fileName)
    }

    @Test
    fun refusesAVersionThatIsNoRelease() {
        assertThrows<IllegalArgumentException> { EmbeddedPayloadPin("3.0", "5".repeat(64), 1) }
        assertThrows<IllegalArgumentException> { EmbeddedPayloadPin("3.0.0-SNAPSHOT!", "5".repeat(64), 1) }
    }

    @Test
    fun refusesAMalformedPin() {
        assertThrows<IllegalArgumentException> { EmbeddedPayloadPin.parse(pin.write().replace("sha256 ", "sha ")) }
        assertThrows<IllegalArgumentException> { EmbeddedPayloadPin.parse(pin.write().replace("size 12345678", "size big")) }
        assertThrows<IllegalArgumentException> { EmbeddedPayloadPin("3.0.0", "5".repeat(63), 1) }
        assertThrows<IllegalArgumentException> { EmbeddedPayloadPin("3.0.0", "5".repeat(64), 512L * 1024 * 1024 + 1) }
    }

    private companion object {
        const val FIXTURE_PROPERTY = "modl.embeddedPayloadPinFixture"
    }
}
