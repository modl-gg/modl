package gg.modl.backend.settings.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;

class SettingsCodecTest {

    private static final StoredSettings DEFAULTS = new StoredSettings();
    private final SettingsCodec<StoredSettings> codec = SettingsCodec.of(StoredSettings.class, () -> DEFAULTS);

    @Test
    void keepsStoredValuesWhenTheDocumentHasKeysTheTypeNoLongerDeclares() {
        StoredSettings decoded = codec.decode(Map.of("name", "Network", "limit", 7, "removedOption", true));

        assertEquals("Network", decoded.getName());
        assertEquals(7, decoded.getLimit());
    }

    @Test
    void readsNullPrimitivesAsTheirDefault() {
        Map<String, Object> document = new HashMap<>();
        document.put("name", "Network");
        document.put("limit", null);

        assertEquals(0, codec.decode(document).getLimit());
    }

    @Test
    void fallsBackToDefaultsWhenAStoredValueHasTheWrongShape() {
        assertSame(DEFAULTS, codec.decode(Map.of("limit", Map.of("nested", "object"))));
    }

    @Test
    void aStrictDecodeRefusesAValueOfTheWrongShape() {
        assertThrows(JacksonException.class, () -> codec.decodeStrict(Map.of("limit", Map.of("nested", "object"))));
    }

    @Test
    void fallsBackToDefaultsForMissingOrEmptyDocuments() {
        assertSame(DEFAULTS, codec.decode(null));
        assertSame(DEFAULTS, codec.decode(Map.of()));
    }

    @Test
    void encodesValuesAsPlainDocuments() {
        StoredSettings settings = new StoredSettings();
        settings.setName("Network");
        settings.setLimit(3);

        assertEquals(Map.of("name", "Network", "limit", 3), codec.encode(settings));
    }

    static final class StoredSettings {
        private String name;
        private int limit;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }
}
