package gg.modl.minecraft.core.imports.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourceSupportTest {
    @Test
    void plainTextDropsLegacyAndRenderedColorCodes() {
        assertEquals("No reason specified", SourceSupport.plainText("&7No &lreason §cspecified"));
    }

    @Test
    void aBlankReasonIsNoReason() {
        assertNull(SourceSupport.trimmedOrNull("   "));
        assertEquals("griefing", SourceSupport.trimmedOrNull(" griefing "));
    }
}
