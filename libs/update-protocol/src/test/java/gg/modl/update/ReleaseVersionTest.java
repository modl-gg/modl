package gg.modl.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReleaseVersionTest {
    @Test
    void parsesStrictVersions() {
        ReleaseVersion version = ReleaseVersion.parse("3.10.2-rc.1");
        assertEquals(3, version.major());
        assertEquals(10, version.minor());
        assertEquals(2, version.patch());
        assertTrue(version.isPrerelease());
        assertEquals("3.10", version.line());
        assertEquals("3.10.2-rc.1", version.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"v3.0.0", "3.0", "3.0.0.0", "03.0.0", "3.0.0+build", "3.0.0-", "3.0.0-rc..1", "3.0.0-01",
        "3.0.0-rc/1", "3.0.0-../x", " 3.0.0", "3.0.0-abcdefghijklmnopqrstuvwxyz0123456", ""})
    void rejectsLooseVersions(String text) {
        assertNull(ReleaseVersion.tryParse(text));
        assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.parse(text));
    }

    @Test
    void rejectsMissingAndOverlongText() {
        assertNull(ReleaseVersion.tryParse(null));
        assertNull(ReleaseVersion.tryParse("1.0.0-" + "a".repeat(70)));
    }

    @Test
    void ordersBySemanticVersioning() {
        List<String> expected = List.of("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0");
        List<ReleaseVersion> shuffled = new ArrayList<>();
        expected.forEach(text -> shuffled.add(ReleaseVersion.parse(text)));
        Collections.reverse(shuffled);
        Collections.sort(shuffled);
        assertEquals(expected, shuffled.stream().map(ReleaseVersion::toString).toList());
    }

    @Test
    void comparesLines() {
        assertTrue(ReleaseVersion.parse("3.1.0").sameLine(ReleaseVersion.parse("3.1.9-rc.1")));
        assertFalse(ReleaseVersion.parse("3.1.0").sameLine(ReleaseVersion.parse("3.2.0")));
        assertTrue(ReleaseVersion.parse("3.1.1").isNewerThan(ReleaseVersion.parse("3.1.1-rc.1")));
        assertEquals(ReleaseVersion.parse("3.1.1"), ReleaseVersion.parse("3.1.1"));
    }
}
