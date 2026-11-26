package gg.modl.minecraft.replay.format;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReplayLimitsTest {
    private static final String FIXTURE = "replay-limits.txt";

    @Test
    void theCommittedFixtureListsEveryLimitWithItsValue() throws IOException, IllegalAccessException {
        assertEquals(constants(), fixture());
    }

    @Test
    void theFixtureIsSortedByName() throws IOException {
        List<String> lines = fixture();
        List<String> sorted = new ArrayList<>(lines);
        sorted.sort(Comparator.comparing(ReplayLimitsTest::nameOf));
        assertEquals(sorted, lines);
    }

    private static List<String> constants() throws IllegalAccessException {
        List<String> lines = new ArrayList<>();
        for (Field field : ReplayLimits.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)) {
                lines.add(field.getName().toLowerCase(Locale.ROOT).replace('_', '-') + " " + field.get(null));
            }
        }
        lines.sort(Comparator.comparing(ReplayLimitsTest::nameOf));
        return lines;
    }

    private static List<String> fixture() throws IOException {
        try (InputStream file = ReplayLimitsTest.class.getResourceAsStream(FIXTURE)) {
            assertNotNull(file, FIXTURE);
            String text = new String(file.readAllBytes(), StandardCharsets.UTF_8);
            return text.lines().toList();
        }
    }

    private static String nameOf(String line) {
        return line.substring(0, line.indexOf(' '));
    }
}
