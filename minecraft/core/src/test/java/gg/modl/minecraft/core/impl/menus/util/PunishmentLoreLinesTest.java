package gg.modl.minecraft.core.impl.menus.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PunishmentLoreLinesTest {
    private static final List<String> HISTORY = Arrays.asList(
            "&7{status_line}", "&f ", "{notes}", "&f ", "&7Server: &f{issued_server}", "&7ID: &f{punishment_id}");
    private static final List<String> RECENT = Arrays.asList(
            "&7Player: &f{player}", "&7{status_line}", "&f ", "{notes}", "&f ", "&7Server: &f{issued_server}",
            "&7ID: &f{punishment_id}");

    @Test
    void aKickWithoutNotesOrServerShowsOnlyItsId() {
        assertEquals(Arrays.asList("&7ID: &fK1"), PunishmentItemRenderer.renderLore(HISTORY, vars("", "", "")));
    }

    @Test
    void aRecentKickKeepsOneSpacerAroundItsNotes() {
        assertEquals(Arrays.asList("&7Player: &fBob", "&f ", "&enote one", "&enote two", "&f ", "&7ID: &fK1"),
                PunishmentItemRenderer.renderLore(RECENT, vars("", "&enote one\n&enote two", "")));
    }

    @Test
    void aRecentKickWithoutNotesKeepsTheGapBeforeItsId() {
        assertEquals(Arrays.asList("&7Player: &fBob", "&f ", "&7ID: &fK1"),
                PunishmentItemRenderer.renderLore(RECENT, vars("", "", "")));
    }

    @Test
    void aBanShowsItsStatusServerAndId() {
        assertEquals(Arrays.asList("&7Expires in 5m", "&f ", "&7Server: &flobby", "&7ID: &fK1"),
                PunishmentItemRenderer.renderLore(HISTORY, vars("Expires in 5m", "", "lobby")));
    }

    private static Map<String, String> vars(String status, String notes, String server) {
        Map<String, String> vars = new HashMap<>();
        vars.put("status_line", status);
        vars.put("notes", notes);
        vars.put("issued_server", server);
        vars.put("punishment_id", "K1");
        vars.put("player", "Bob");
        return vars;
    }
}
