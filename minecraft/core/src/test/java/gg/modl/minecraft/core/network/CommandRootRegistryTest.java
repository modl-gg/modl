package gg.modl.minecraft.core.network;

import gg.modl.minecraft.core.command.ConfiguredCommandAliases;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRootRegistryTest {
    private final CommandRootRegistry registry = CommandRootRegistry.builder(aliases())
            .registered("ban")
            .registered("freeze")
            .registered("vanish")
            .build();

    @Test
    void resolvesEveryConfiguredAliasOfARegisteredRootIgnoringCaseAndALeadingSlash() {
        assertEquals(Optional.of("ban"), registry.rootKeyOf("ban Steve 1d"));
        assertEquals(Optional.of("ban"), registry.rootKeyOf("/TempBan Steve"));
        assertEquals(Optional.of("freeze"), registry.rootKeyOf("  FREEZE Steve"));
    }

    @Test
    void refusesRootsThatAreNotModlCommandsOrNotRegistered() {
        assertFalse(registry.rootKeyOf("op Steve").isPresent());
        assertFalse(registry.rootKeyOf("kick Steve").isPresent());
        assertFalse(registry.rootKeyOf("").isPresent());
        assertFalse(registry.rootKeyOf("/").isPresent());
    }

    @Test
    void aRootWhoseAliasesAreAllDisabledIsNotEnabled() {
        assertTrue(registry.isEnabled("freeze"));
        assertTrue(registry.isEnabled("Ban"));
        assertFalse(registry.isEnabled("vanish"));
        assertFalse(registry.isEnabled("kick"));
        assertFalse(registry.rootKeyOf("vanish").isPresent());
    }

    @Test
    void theFirstTokenIsLowercasedWithoutTheSlash() {
        assertEquals("staffmenu", CommandRootRegistry.firstToken("/StaffMenu now"));
        assertEquals("", CommandRootRegistry.firstToken(null));
    }

    private static ConfiguredCommandAliases aliases() {
        Map<String, String> raw = new HashMap<>();
        raw.put("ban", "ban|tempban");
        raw.put("kick", "kick");
        raw.put("freeze", "freeze|ss");
        raw.put("vanish", "");
        return new ConfiguredCommandAliases(raw);
    }
}
