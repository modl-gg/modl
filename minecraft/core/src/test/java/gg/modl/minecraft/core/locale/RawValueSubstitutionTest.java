package gg.modl.minecraft.core.locale;

import org.junit.jupiter.api.Test;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RawValueSubstitutionTest {
    private final YamlMessageResolver resolver = new YamlMessageResolver(message -> message);

    @Test
    void aValueNamingAnotherPlaceholderStaysLiteral() {
        assertEquals("{b} {a}", resolver.substituteRawValues("{a} {b}", mapOf("a", "{b}", "b", "{a}")));
    }

    @Test
    void placeholdersWithoutAValueAndSpecialCharactersAreKept() {
        assertEquals("$1\\ {other}", resolver.substituteRawValues("{a} {other}", mapOf("a", "$1\\")));
    }
}
