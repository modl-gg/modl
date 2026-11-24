package gg.modl.minecraft.core.util;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicMethodsTest {
    public interface Versioned {
        String version();

        String version(int part);
    }

    @Test
    void findsOnlyPublicMethodsWithoutParameters() {
        List<Method> matches = PublicMethods.withoutParameters(Versioned.class, "version");

        assertEquals(1, matches.size());
        assertEquals(0, matches.get(0).getParameterCount());
    }

    @Test
    void aMissingMethodIsAnEmptyResultRatherThanAnException() {
        assertTrue(PublicMethods.withoutParameters(Versioned.class, "getBlockData").isEmpty());
    }
}
