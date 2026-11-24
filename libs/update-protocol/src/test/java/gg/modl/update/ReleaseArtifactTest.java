package gg.modl.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReleaseArtifactTest {
    private static final ReleaseVersion VERSION = ReleaseVersion.parse("3.0.0");
    private static final String SHA256 = "ab".repeat(32);

    @Test
    void aPinnedArtifactIsNamedAfterItsKindAndVersion() {
        ReleaseArtifact pinned = ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, VERSION, SHA256, 42);

        assertEquals("modl-embedded-3.0.0.zip", pinned.file());
        assertEquals(SHA256, pinned.sha256());
        assertEquals(42, pinned.size());
    }

    @Test
    void aPinWithAnInvalidHashOrSizeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, VERSION, "AB".repeat(32), 42));
        assertThrows(IllegalArgumentException.class, () -> ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, VERSION, SHA256, 0));
        assertThrows(IllegalArgumentException.class,
            () -> ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, VERSION, SHA256, ReleaseArtifact.MAX_SIZE + 1));
    }

    @Test
    void artifactsAreEqualOnlyWithTheSameFileHashAndSize() {
        ReleaseArtifact pinned = ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, VERSION, SHA256, 42);

        assertEquals(pinned, ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, VERSION, SHA256, 42));
        assertNotEquals(pinned, ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, VERSION, SHA256, 43));
        assertNotEquals(pinned, ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, ReleaseVersion.parse("3.0.1"), SHA256, 42));
        assertNotEquals(pinned, ReleaseArtifact.pinned(ArtifactKind.EMBEDDED, VERSION, "cd".repeat(32), 42));
    }
}
