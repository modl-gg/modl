package dev.simplix.cirrus.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyColorCodesTest {

    @Test
    void everyColourAndFormatCodeBecomesALowerCaseSectionCode() {
        for (char code : "0123456789abcdefklmnorABCDEFKLMNOR".toCharArray()) {
            assertEquals("§" + Character.toLowerCase(code) + "x",
                    LegacyColorCodes.translateAmpersandCodes("&" + code + "x"), String.valueOf(code));
        }
    }

    @Test
    void anAmpersandBeforeAnythingElseStaysLiteral() {
        for (String text : new String[]{"Tom & Jerry", "&z", "&x", "&#ff0000", "a&", "&", "&&", "&Z", "&\u212a", "Salt &pepper", "&-1"}) {
            assertEquals(text, LegacyColorCodes.translateAmpersandCodes(text), text);
        }
    }

    @Test
    void anAmpersandHexMarkerIsNotAColourCode() {
        assertEquals("&x§f§f§0§0§0§0", LegacyColorCodes.translateAmpersandCodes("&x&f&f&0&0&0&0"));
    }

    @Test
    void sectionCodesAndPlainTextPassThrough() {
        assertEquals("§cRed §x plain", LegacyColorCodes.translateAmpersandCodes("§cRed §x plain"));
    }

    @Test
    void aDoubledAmpersandKeepsTheFirstAndTranslatesTheCodeAfterTheSecond() {
        assertEquals("&§c", LegacyColorCodes.translateAmpersandCodes("&&c"));
    }
}
