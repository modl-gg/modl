package gg.modl.minecraft.core.locale;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyTextRendererTest {

    @Test
    void detectsMiniMessageTags() {
        assertTrue(LegacyTextRenderer.isMiniMessage("<red>Denied"));
        assertTrue(LegacyTextRenderer.isMiniMessage("<#55ff55>Allowed"));
        assertFalse(LegacyTextRenderer.isMiniMessage("&cDenied"));
    }

    @Test
    void colorizeTranslatesLegacyAmpersandCodes() {
        assertEquals("\u00a7cDenied \u00a7lnow", LegacyTextRenderer.colorize("&cDenied &lnow"));
    }

    @Test
    void colorizeLeavesAmpersandsThatAreNotColourCodes() {
        assertEquals("Tom & Jerry &z &x", LegacyTextRenderer.colorize("Tom & Jerry &z &x"));
    }

    @Test
    void colorizeTranslatesCommonMiniMessageTagsWithoutKyoriRuntime() {
        assertEquals("\u00a7cDenied \u00a7lnow", LegacyTextRenderer.colorize("<red>Denied <bold>now</bold></red>"));
    }

    @Test
    void colorizeKeepsCommandPlaceholdersThatAreNoTags() {
        assertEquals("Run /modl import <source> confirm <widen|skip> or /modl link <code>.",
                LegacyTextRenderer.colorize("Run /modl import <source> confirm <widen|skip> or /modl link <code>."));
    }

    @Test
    void colorizeKnowsMiniMessageShorthandsAndDropsEffectsItCannotRender() {
        assertEquals("\u00a7lbold \u00a7oit\nnext rainbow", LegacyTextRenderer.colorize("<b>bold</b> <i>it</i><br>next <rainbow>rainbow</rainbow>"));
    }

    @Test
    void colorizeKeepsAClosingPlaceholderThatIsNoTag() {
        assertEquals("<value> and </value>", LegacyTextRenderer.colorize("<value> and </value>"));
    }

    @Test
    void colorizeKeepsTextReadableWhenTagsAreUnsupported() {
        assertEquals("Click here", LegacyTextRenderer.colorize("<click:run_command:'/help'>Click here</click>"));
    }

    @Test
    void colorizeTreatsBothEndsOfAnUnknownArgumentTagAlike() {
        assertEquals("<x:y>text</x:y>", LegacyTextRenderer.colorize("<x:y>text</x:y>"));
    }

    @Test
    void colorizeKnowsNamedAndHexColorArguments() {
        assertEquals("\u00a7cred\u00a7x\u00a71\u00a72\u00a73\u00a74\u00a75\u00a76hex",
                LegacyTextRenderer.colorize("<color:red>red</color><c:#123456>hex</c>"));
    }
}
