package gg.modl.minecraft.core.punishment;

import gg.modl.minecraft.api.SimplePunishment;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.locale.PunishmentMessageContext;
import gg.modl.minecraft.core.support.TempConfigs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerFacingReasonMessagesTest {
    private static final String STAFF_NOTE = "suspected alt of Herobrine";
    private static final String ISSUED_REASON = "Spamming in public chat";
    private static final String TYPE_DESCRIPTION = "Follow the server rules";
    private static final String DEFAULT_REASON = "No reason given";

    @TempDir
    Path folder;

    private PunishmentMessageService messages;

    private enum Surface {
        LOGIN_BAN(2, 10) {
            @Override
            String render(PunishmentMessageService messages, SimplePunishment punishment) {
                return messages.formatBanMessage(punishment, PunishmentMessageContext.LOGIN);
            }
        },
        SYNC_BAN(2, 10) {
            @Override
            String render(PunishmentMessageService messages, SimplePunishment punishment) {
                return messages.formatBanMessage(punishment, PunishmentMessageContext.SYNC);
            }
        },
        KICK(0, 18) {
            @Override
            String render(PunishmentMessageService messages, SimplePunishment punishment) {
                return messages.formatKickMessage(punishment, PunishmentMessageContext.SYNC);
            }
        },
        MUTE(1, 6) {
            @Override
            String render(PunishmentMessageService messages, SimplePunishment punishment) {
                return messages.getMuteMessage(punishment);
            }
        },
        BROADCAST(2, 4) {
            @Override
            String render(PunishmentMessageService messages, SimplePunishment punishment) {
                return messages.formatPunishmentBroadcast("Player", punishment);
            }
        };

        private final int manualOrdinal;
        private final int otherOrdinal;

        Surface(int manualOrdinal, int otherOrdinal) {
            this.manualOrdinal = manualOrdinal;
            this.otherOrdinal = otherOrdinal;
        }

        abstract String render(PunishmentMessageService messages, SimplePunishment punishment);
    }

    @BeforeEach
    void showReasonAndDescriptionInEveryTemplate() throws IOException {
        Path overrides = folder.resolve("en_US.yml");
        TempConfigs.write(overrides,
                "punishment_types:",
                template(0), template(1), template(2), template(4), template(6), template(10), template(18));
        LocaleManager locale = new LocaleManager();
        locale.loadFromFile(overrides);
        locale.setConfigValues(Collections.singletonMap("default_reason", DEFAULT_REASON));
        messages = new PunishmentMessageService(locale,
                PanelUrl.of("https://panel.example.com"));
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void aManualPunishmentShowsTheReasonWrittenForThePlayer(Surface surface) {
        String message = surface.render(messages, punishment(surface.manualOrdinal, ISSUED_REASON, TYPE_DESCRIPTION));

        assertShows(message, ISSUED_REASON);
        assertFalse(message.contains(TYPE_DESCRIPTION));
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void aStaffNoteSentAsTheDescriptionIsReplacedByTheTypeDescription(Surface surface) {
        String message = surface.render(messages, punishment(surface.otherOrdinal, STAFF_NOTE, TYPE_DESCRIPTION));

        assertShows(message, TYPE_DESCRIPTION);
        assertFalse(message.contains(STAFF_NOTE));
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void aStaffNoteIsNotShownWhenTheTypeHasNoDescription(Surface surface) {
        String message = surface.render(messages, punishment(surface.otherOrdinal, STAFF_NOTE, null));

        assertShows(message, DEFAULT_REASON);
        assertFalse(message.contains(STAFF_NOTE));
    }

    @ParameterizedTest
    @EnumSource(Surface.class)
    void aManualPunishmentWithoutAReasonShowsTheTypeDescription(Surface surface) {
        assertShows(surface.render(messages, punishment(surface.manualOrdinal, "", TYPE_DESCRIPTION)), TYPE_DESCRIPTION);
    }

    private static void assertShows(String message, String reason) {
        assertTrue(message.contains("reason=" + reason + ";"), message);
        assertTrue(message.contains("description=" + reason + ";"), message);
    }

    private static String template(int ordinal) {
        String line = "reason={reason};description={description};";
        return "  ordinal_" + ordinal + ":\n"
                + "    public_notification: \"" + line + "\"\n"
                + "    player_notification:\n"
                + "      - \"" + line + "\"";
    }

    private static SimplePunishment punishment(int ordinal, String description, String typeDescription) {
        return new SimplePunishment("Punishment", null, null, description, "ABCD1234", "Admin", null, typeDescription,
                true, ordinal, false);
    }
}
