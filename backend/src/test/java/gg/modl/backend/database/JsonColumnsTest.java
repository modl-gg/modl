package gg.modl.backend.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonColumnsTest {
    private final JsonColumns json = new JsonColumns();

    @Test
    void aKeyLongerThanJacksonsDefaultLimitCanBeReadBack() {
        Map<String, Object> value = Map.of("k".repeat(60_000), "value");

        assertEquals(value, json.readMap(json.write(value)));
    }
}
