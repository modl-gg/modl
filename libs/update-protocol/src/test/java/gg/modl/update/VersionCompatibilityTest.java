package gg.modl.update;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VersionCompatibilityTest {
    @ParameterizedTest
    @CsvSource({
        "3.0.0, 3.0.5, COMPATIBLE",
        "3.0.5, 3.0.0, COMPATIBLE",
        "3.0.1-rc.1, 3.0.0, COMPATIBLE",
        "3.0.0, 3.1.0, PLUGIN_OLDER",
        "3.1.0, 3.0.9, SERVER_OLDER",
        "2.9.9, 3.0.0, PLUGIN_OLDER",
        "4.0.0, 3.9.0, SERVER_OLDER",
        "3.1.0-rc.1, 3.0.0, SERVER_OLDER",
        "dev, 3.0.0, UNKNOWN",
        "3.0.0, '', UNKNOWN",
    })
    void comparesMajorAndMinorOnly(String plugin, String server, VersionCompatibility expected) {
        assertEquals(expected, VersionCompatibility.between(plugin, server));
    }

    @ParameterizedTest
    @CsvSource({"PLUGIN_OLDER, PLUGIN_OLDER", "SERVER_OLDER, SERVER_OLDER", "COMPATIBLE, UNKNOWN", "garbage, UNKNOWN"})
    void readsRefusalHeaders(String header, VersionCompatibility expected) {
        assertEquals(expected, VersionCompatibility.fromHeader(header));
    }
}
