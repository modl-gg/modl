package gg.modl.minecraft.core.impl.menus.util;

import gg.modl.minecraft.core.PluginServices;
import dev.simplix.cirrus.model.CirrusClickType;
import dev.simplix.cirrus.model.Click;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.Evidence;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.api.http.ModlHttpClient;
import gg.modl.minecraft.api.http.request.AddPunishmentEvidenceRequest;
import gg.modl.minecraft.api.http.request.AddPunishmentNoteRequest;
import gg.modl.minecraft.api.http.request.ChangePunishmentDurationRequest;
import gg.modl.minecraft.api.http.request.ModifyPunishmentTicketsRequest;
import gg.modl.minecraft.api.http.request.PardonPunishmentRequest;
import gg.modl.minecraft.api.http.request.TogglePunishmentOptionRequest;
import gg.modl.minecraft.core.Platform;

import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.impl.menus.LinkReportsMenu;
import gg.modl.minecraft.core.impl.menus.ViewLinkedTicketsMenu;
import gg.modl.minecraft.core.impl.menus.base.MenuChrome;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.AuthorizedStaff;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import gg.modl.minecraft.core.util.ApiFailures;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class PunishmentModificationActions {

    private static final long MS_PER_SECOND = 1000L, MS_PER_MINUTE = 60 * MS_PER_SECOND, MS_PER_HOUR = 60 * MS_PER_MINUTE, MS_PER_DAY = 24 * MS_PER_HOUR;
    private static final String KEY = "menus.modify_punishment.";
    private static final String PERMANENT_KEYWORD = "perm";

    private final Platform platform;
    private final ModlHttpClient httpClient;
    private final UUID viewerUuid;
    private final StaffViewer viewer;
    private final String viewerName;
    private final UUID targetUuid;
    private final Punishment punishment;
    private final Consumer<String> sendMessage;
    private final Consumer<Click> refreshMenu;
    private final Consumer<CirrusPlayerWrapper> displayMenu;

    public PunishmentModificationActions(Platform platform, ModlHttpClient httpClient,
                                          UUID viewerUuid, String viewerName, UUID targetUuid,
                                          Punishment punishment, Consumer<String> sendMessage,
                                          Consumer<Click> refreshMenu, Consumer<CirrusPlayerWrapper> displayMenu) {
        this.platform = platform;
        this.httpClient = httpClient;
        this.viewerUuid = viewerUuid;
        this.viewer = new StaffViewer(platform, viewerUuid);
        this.viewerName = viewerName;
        this.targetUuid = targetUuid;
        this.punishment = punishment;
        this.sendMessage = sendMessage;
        this.refreshMenu = refreshMenu;
        this.displayMenu = displayMenu;
    }

    public void registerHandlers(BiConsumer<String, Consumer<Click>> registrar, MenuChrome chrome, Account target,
                                 Consumer<CirrusPlayerWrapper> reopenMenu) {
        registrar.accept("addNote", this::handleAddNote);
        registrar.accept("evidence", this::handleEvidence);
        registrar.accept("pardon", this::handlePardon);
        registrar.accept("changeDuration", this::handleChangeDuration);
        registrar.accept("linkedTickets", click -> handleLinkedTickets(click, chrome, target, reopenMenu));
        registrar.accept("toggleStatWipe", this::handleToggleStatWipe);
        registrar.accept("toggleAltBlock", this::handleToggleAltBlock);
    }

    private static String text(String key) {
        return PluginServices.locale().getMessage(key);
    }

    private static String error(String key, Throwable throwable) {
        return ApiFailures.describe(PluginServices.locale(), key, throwable);
    }

    private static boolean isPermanentKeyword(String input) {
        return input.equalsIgnoreCase(PERMANENT_KEYWORD) || input.equalsIgnoreCase("permanent");
    }

    public void handleAddNote(Click click) {
        viewer.promptFromMenu(click, StaffRequirements.ADD_PUNISHMENT_NOTE, text(KEY + "add_note.prompt"),
                (issuer, input) -> {
                    AddPunishmentNoteRequest request = new AddPunishmentNoteRequest(
                            punishment.getId(), viewerName, issuer.actionIssuer(), input);

                    httpClient.addPunishmentNote(request).thenAccept(v -> {
                        sendMessage.accept(text(KEY + "add_note.added"));
                        refreshMenu.accept(click);
                    }).exceptionally(e -> {
                        sendMessage.accept(error(KEY + "add_note.failed", e));
                        displayMenu.accept(click.player());
                        return null;
                    });
                },
                () -> {
                    sendMessage.accept(text(KEY + "add_note.cancelled"));
                    displayMenu.accept(click.player());
                }
        );
    }

    public void handleEvidence(Click click) {
        if (!viewer.authorize(StaffRequirements.MANAGE_PUNISHMENT_EVIDENCE)) return;
        if (click.clickType().equals(CirrusClickType.RIGHT_CLICK)) {
            List<Evidence> evidenceList = punishment.getEvidence();
            if (evidenceList.isEmpty()) {
                sendMessage.accept(text(KEY + "evidence.no_evidence"));
                return;
            }

            sendMessage.accept("");
            LocaleManager locale = PluginServices.locale();
            sendMessage.accept(locale.getMessage(KEY + "evidence.chat_header", mapOf("id", String.valueOf(punishment.getId()))));
            for (int i = 0; i < evidenceList.size(); i++) {
                Evidence ev = evidenceList.get(i);
                sendMessage.accept(locale.getMessageWithRawValues(KEY + "evidence.chat_entry",
                        mapOf("index", String.valueOf(i + 1), "evidence", String.valueOf(ev.getDisplayText()))));
                sendMessage.accept(locale.getMessage(KEY + "evidence.chat_added_by",
                        mapOf("uploader", String.valueOf(ev.getUploadedBy()), "date", MenuItems.formatDate(ev.getUploadedAt()))));
            }
            sendMessage.accept("");
            return;
        }

        viewer.promptFromMenu(click, StaffRequirements.MANAGE_PUNISHMENT_EVIDENCE, text(KEY + "evidence.prompt"),
                (issuer, input) -> {
                    AddPunishmentEvidenceRequest request = new AddPunishmentEvidenceRequest(
                            punishment.getId(), viewerName, issuer.actionIssuer(), input);

                    httpClient.addPunishmentEvidence(request).thenAccept(v -> {
                        sendMessage.accept(text(KEY + "evidence.added"));
                        refreshMenu.accept(click);
                    }).exceptionally(e -> {
                        sendMessage.accept(error(KEY + "evidence.failed", e));
                        displayMenu.accept(click.player());
                        return null;
                    });
                },
                () -> {
                    sendMessage.accept(text(KEY + "evidence.cancelled"));
                    displayMenu.accept(click.player());
                }
        );
    }

    public void handlePardon(Click click) {
        viewer.authorizeAs(StaffRequirements.PARDON_PUNISHMENT).ifPresent(issuer -> pardon(click, issuer));
    }

    private void pardon(Click click, AuthorizedStaff issuer) {
        PardonPunishmentRequest request = new PardonPunishmentRequest(
                punishment.getId(), viewerName, issuer.actionIssuer(), null, null);

        httpClient.pardonPunishment(request).thenAccept(response -> {
            if (response.hasPardoned()) {
                sendMessage.accept(text(KEY + "pardon.success"));
                updateTargetProfile(profile -> profile.endPunishment(punishment.getId()));
                click.clickedMenu().close();
                refreshMenu.accept(click);
            } else {
                sendMessage.accept(text(KEY + "pardon.already_inactive"));
            }
        }).exceptionally(e -> {
            sendMessage.accept(error(KEY + "pardon.failed", e));
            return null;
        });
    }

    public void handleChangeDuration(Click click) {
        viewer.promptFromMenu(click, StaffRequirements.CHANGE_PUNISHMENT_DURATION,
                PluginServices.locale().getMessage(KEY + "duration.prompt", mapOf("permanent_keyword", PERMANENT_KEYWORD)),
                (issuer, input) -> {
                    Long durationMs = parseDuration(input);
                    if (durationMs == null && !isPermanentKeyword(input)) {
                        sendMessage.accept(text(KEY + "duration.invalid"));
                        displayMenu.accept(click.player());
                        return;
                    }
                    if (punishment.endsEarlierWith(durationMs, System.currentTimeMillis())
                            && !StaffRequirements.allowsShortening(viewer::allows)) {
                        sendMessage.accept(text(ApiFailures.SHORTENING_NEEDS_PARDON_KEY));
                        displayMenu.accept(click.player());
                        return;
                    }

                    ChangePunishmentDurationRequest request = new ChangePunishmentDurationRequest(
                            punishment.getId(), viewerName, issuer.actionIssuer(), durationMs);

                    httpClient.changePunishmentDuration(request).thenAccept(v -> {
                        sendMessage.accept(text(KEY + "duration.changed"));
                        long changedAt = System.currentTimeMillis();
                        updateTargetProfile(profile -> profile.changePunishmentDuration(punishment.getId(), durationMs, changedAt));
                        refreshMenu.accept(click);
                    }).exceptionally(e -> {
                        sendMessage.accept(error(KEY + "duration.failed", e));
                        displayMenu.accept(click.player());
                        return null;
                    });
                },
                () -> {
                    sendMessage.accept(text(KEY + "duration.cancelled"));
                    displayMenu.accept(click.player());
                }
        );
    }

    public void handleToggleStatWipe(Click click) {
        viewer.authorizeAs(StaffRequirements.CHANGE_PUNISHMENT_OPTIONS).ifPresent(issuer -> toggleStatWipe(click, issuer));
    }

    private void toggleStatWipe(Click click, AuthorizedStaff issuer) {
        boolean currentStatus = punishment.isWipeAfterExpiry();

        TogglePunishmentOptionRequest request = new TogglePunishmentOptionRequest(
                punishment.getId(), viewerName, issuer.actionIssuer(), "STAT_WIPE", !currentStatus);

        httpClient.togglePunishmentOption(request).thenAccept(v -> {
            sendMessage.accept(text(KEY + (currentStatus ? "stat_wipe.disabled" : "stat_wipe.enabled")));
            refreshMenu.accept(click);
        }).exceptionally(e -> {
            sendMessage.accept(error(KEY + "stat_wipe.failed", e));
            return null;
        });
    }

    public void handleToggleAltBlock(Click click) {
        viewer.authorizeAs(StaffRequirements.CHANGE_PUNISHMENT_OPTIONS).ifPresent(issuer -> toggleAltBlock(click, issuer));
    }

    private void toggleAltBlock(Click click, AuthorizedStaff issuer) {
        boolean currentStatus = Boolean.TRUE.equals(punishment.getDataMap().get("altBlocking"));

        TogglePunishmentOptionRequest request = new TogglePunishmentOptionRequest(
                punishment.getId(), viewerName, issuer.actionIssuer(), "ALT_BLOCKING", !currentStatus);

        httpClient.togglePunishmentOption(request).thenAccept(v -> {
            sendMessage.accept(text(KEY + (currentStatus ? "alt_block.disabled" : "alt_block.enabled")));
            refreshMenu.accept(click);
        }).exceptionally(e -> {
            sendMessage.accept(error(KEY + "alt_block.failed", e));
            return null;
        });
    }

    private void handleLinkedTickets(Click click, MenuChrome chrome, Account target,
                                     Consumer<CirrusPlayerWrapper> reopenMenu) {
        List<String> linkedIds = punishment.getAttachedTicketIds();
        if (click.clickType().equals(CirrusClickType.RIGHT_CLICK)) {
            if (!viewer.authorize(StaffRequirements.LINK_PUNISHMENT_TICKETS)) return;
            LinkReportsMenu linkMenu = new LinkReportsMenu(platform, httpClient, viewerUuid, viewerName, chrome, target,
                    new LinkedHashSet<>(linkedIds), reopenMenu, selectedIds -> saveLinkedTickets(click, selectedIds, reopenMenu));
            MenuAsync.displayWhenLoaded(platform, linkMenu.getDataFuture(), click.player(), linkMenu::display);
            return;
        }
        if (!viewer.authorize(StaffRequirements.STAFF)) return;
        ViewLinkedTicketsMenu viewMenu = new ViewLinkedTicketsMenu(platform, httpClient, viewerUuid, viewerName, chrome,
                linkedIds, reopenMenu);
        MenuAsync.displayWhenLoaded(platform, viewMenu.getDataFuture(), click.player(), viewMenu::display);
    }

    void saveLinkedTickets(Click click, Set<String> selectedIds, Consumer<CirrusPlayerWrapper> reopenMenu) {
        viewer.authorizeAs(StaffRequirements.LINK_PUNISHMENT_TICKETS)
                .ifPresent(issuer -> saveLinkedTickets(click, selectedIds, reopenMenu, issuer));
    }

    private void saveLinkedTickets(Click click, Set<String> selectedIds, Consumer<CirrusPlayerWrapper> reopenMenu,
                                   AuthorizedStaff issuer) {
        List<String> linkedIds = punishment.getAttachedTicketIds();
        List<String> added = new ArrayList<>(selectedIds);
        added.removeAll(linkedIds);
        List<String> removed = new ArrayList<>(linkedIds);
        removed.removeAll(selectedIds);
        if (added.isEmpty() && removed.isEmpty()) {
            sendMessage.accept(text(KEY + "no_changes"));
            reopenMenu.accept(click.player());
            return;
        }

        ModifyPunishmentTicketsRequest request = new ModifyPunishmentTicketsRequest(
                punishment.getId(), viewerName, issuer.actionIssuer(), added, removed, true);

        httpClient.modifyPunishmentTickets(request).thenAccept(v -> {
            sendMessage.accept(text(KEY + "tickets_updated"));
            refreshMenu.accept(click);
        }).exceptionally(e -> {
            sendMessage.accept(error(KEY + "tickets_update_error", e));
            platform.runOnMainThread(() -> reopenMenu.accept(click.player()));
            return null;
        });
    }

    private void updateTargetProfile(Consumer<CachedProfile> update) {
        CachedProfile profile = PluginServices.cache().getPlayerProfile(targetUuid);
        if (profile != null) update.accept(profile);
    }

    public static @Nullable Long parseDuration(@Nullable String input) {
        if (input == null || input.isEmpty()) return null;
        if (isPermanentKeyword(input)) return null;

        long total = 0;
        StringBuilder number = new StringBuilder();

        for (char c : input.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isDigit(c)) {
                number.append(c);
            } else if (c == 'd' || c == 'h' || c == 'm' || c == 's') {
                if (number.length() == 0) continue;
                long value = Long.parseLong(number.toString());
                number.setLength(0);

                switch (c) {
                    case 'd': total += value * MS_PER_DAY; break;
                    case 'h': total += value * MS_PER_HOUR; break;
                    case 'm': total += value * MS_PER_MINUTE; break;
                    case 's': total += value * MS_PER_SECOND; break;
                }
            }
        }

        return total > 0 ? total : null;
    }
}
