package gg.modl.minecraft.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HexTest {
    @Test
    void decodesWhatItEncodes() {
        byte[] bytes = {0, 1, 15, 16, 127, -128, -1};

        assertEquals("00010f107f80ff", Hex.encode(bytes));
        assertArrayEquals(bytes, Hex.decode(Hex.encode(bytes)));
        assertArrayEquals(bytes, Hex.decode("00010F107F80FF"));
    }

    @Test
    void rejectsTextThatIsNotHex() {
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("abc"));
        assertThrows(IllegalArgumentException.class, () -> Hex.decode("zz"));
    }
}
