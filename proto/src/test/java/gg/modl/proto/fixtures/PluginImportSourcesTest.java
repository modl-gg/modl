package gg.modl.proto.fixtures;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginImportSourcesTest {

    @Test
    void theFixtureNamesExactlyTheSourcesTheWireAllowsInItsOrder() {
        assertEquals(PluginImportSources.wireIds(), new ArrayList<>(PluginImportSources.displayNamesById().keySet()));
    }
}
