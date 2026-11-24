package gg.modl.backend.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EntityIdsTest {
    private static final int SAMPLE_SIZE = 100_000;
    private static final int ALPHABET_SIZE = 32;

    @Test
    void everyIdIsSixteenCharactersOfTheAlphabet() {
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            String id = EntityIds.next();
            assertEquals(EntityIds.LENGTH, id.length(), id);
            assertTrue(id.chars().allMatch(character -> EntityIds.ALPHABET.indexOf(character) >= 0), id);
        }
    }

    @Test
    void oneHundredThousandIdsAreDistinct() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < SAMPLE_SIZE; i++) {
            ids.add(EntityIds.next());
        }
        assertEquals(SAMPLE_SIZE, ids.size());
    }

    @Test
    void theAlphabetIsLowerCaseCrockfordBase32() {
        Set<Character> characters = EntityIds.ALPHABET.chars().mapToObj(character -> (char) character).collect(Collectors.toSet());

        assertEquals(ALPHABET_SIZE, characters.size());
        assertEquals(EntityIds.ALPHABET.toLowerCase(Locale.ROOT), EntityIds.ALPHABET);
        assertTrue(EntityIds.ALPHABET.chars().noneMatch(character -> "ilou".indexOf(character) >= 0));
    }

    @Test
    void generatedIdsUseEveryCharacterOfTheAlphabet() {
        Set<Character> used = new HashSet<>();
        for (int i = 0; i < SAMPLE_SIZE / 10; i++) {
            EntityIds.next().chars().forEach(character -> used.add((char) character));
        }
        assertEquals(ALPHABET_SIZE, used.size());
    }
}
