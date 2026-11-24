package gg.modl.minecraft.core.impl.menus.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentTitleKindTest {
    @Test
    void aTypeNameThatNamesTheKindAsAWordNeedsNoKindSuffix() {
        assertTrue(PunishmentItemRenderer.namesKind("Manual Mute", "Mute"));
        assertTrue(PunishmentItemRenderer.namesKind("Security ban", "Ban"));
    }

    @Test
    void aKindHiddenInsideAnotherWordStillGetsTheSuffix() {
        assertFalse(PunishmentItemRenderer.namesKind("Urban griefing", "Ban"));
        assertFalse(PunishmentItemRenderer.namesKind("Chat Abuse", "Mute"));
    }
}
