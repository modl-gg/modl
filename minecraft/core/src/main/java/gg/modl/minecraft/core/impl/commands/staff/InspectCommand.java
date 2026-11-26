package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.core.command.RequiresPlayerView;
import gg.modl.minecraft.core.connection.PanelUrl;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.Modification;
import gg.modl.minecraft.api.Note;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.api.http.request.PlayerLookupRequest;
import gg.modl.minecraft.api.http.response.LinkedAccountsResponse;
import gg.modl.minecraft.api.http.response.PlayerLookupResponse;
import gg.modl.minecraft.api.http.response.PunishmentDetailResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.command.StaffOnly;
import gg.modl.minecraft.core.util.ClickableJsonMessage;
import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.util.CommandUtil;
import gg.modl.minecraft.core.punishment.PunishmentTypeCacheManager;
import gg.modl.minecraft.core.staff.action.InspectActions;
import gg.modl.minecraft.core.staff.action.StaffActors;
import gg.modl.minecraft.core.util.NoteDisplay;
import gg.modl.minecraft.core.util.TimeUtil;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.command.CommandActor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
public class InspectCommand {
    private static final String COLOR_YES = "&c", COLOR_NO = "&a", COLOR_ACTIVE = "&a", COLOR_INACTIVE = "&7";
    private static final String STATUS_ACTIVE = "status_active", STATUS_INACTIVE = "status_inactive",
            STATUS_PARDONED = "status_pardoned", STATUS_ROLLED_BACK = "status_rolled_back";
    private static final int MAX_NOTES_DISPLAYED = 3, MAX_LINKED_ACCOUNTS_DISPLAYED = 5;

    private final HttpClientHolder httpClientHolder;
    private final Platform platform;
    private final Cache cache;
    private final LocaleManager localeManager;
    private final PanelUrl panelUrl;
    private final PunishmentTypeCacheManager punishmentTypeCache;
    private final InspectActions inspectActions;

    @Command("inspect")
    @Description("Open the inspect menu for a player, or use -p to print info to chat")
    @StaffOnly
    @RequiresPlayerView
    public void inspect(CommandActor actor, @Named("player") String playerQuery, @Optional @Nullable String flags) {
        if (flags == null) flags = "";

        if (playerQuery.startsWith("#")) {
            String punishmentId = playerQuery.substring(1);
            printPunishmentDetail(actor, punishmentId);
            return;
        }

        boolean printMode = flags.equalsIgnoreCase("-p") || flags.equalsIgnoreCase("print");

        if (CommandUtil.rendersAsText(actor, platform, printMode)) {
            printLookup(actor, playerQuery);
            return;
        }

        inspectActions.openMenu(StaffActors.of(actor), playerQuery);
    }

    private void printPunishmentDetail(CommandActor actor, String punishmentId) {

        httpClientHolder.getClient().getPunishmentDetail(punishmentId).thenAccept(response -> {
            if (!response.isSuccess() || response.getPunishment() == null) {
                actor.reply(localeManager.getMessage("print.punishment_detail.not_found", mapOf("id", punishmentId)));
                return;
            }

            PunishmentDetailResponse.PunishmentDetail p = response.getPunishment();
            String typeName = getPunishmentTypeName(p.getTypeOrdinal());
            String playerName = p.getPlayerName() != null ? p.getPlayerName() : localeManager.getUnknownWord();
            String issuerName = p.getIssuerName() != null ? p.getIssuerName() : localeManager.getUnknownWord();
            String issued = p.getIssued() != null ? p.getIssued() : localeManager.getUnknownWord();

            Map<String, Object> data = p.getData();
            String statusKey = detailStatusKey(p);
            boolean active = STATUS_ACTIVE.equals(statusKey);

            String status = localeManager.getMessage("print.punishment_detail." + statusKey);
            Long effectiveDuration = p.getEffectiveDuration();
            String duration = effectiveDuration != null
                    ? TimeUtil.formatTimeMillis(effectiveDuration)
                    : localeManager.getMessage("general.permanent");

            Object storedReason = data != null ? data.get("reason") : null;
            String reason = localeManager.reasonOrDefault(storedReason instanceof String ? (String) storedReason : null);

            int evidenceCount = p.getEvidence() != null ? p.getEvidence().size() : 0;
            int notesCount = p.getNotes() != null ? p.getNotes().size() : 0;
            int modsCount = p.getModifications() != null ? p.getModifications().size() : 0;

            actor.reply(localeManager.getMessage("print.punishment_detail.header", mapOf("id", punishmentId)));
            actor.reply(localeManager.getMessage("print.punishment_detail.type", mapOf("type", typeName)));
            actor.reply(localeManager.getMessage("print.punishment_detail.player", mapOf("player", playerName)));
            actor.reply(localeManager.getMessage("print.punishment_detail.issuer", mapOf("issuer", issuerName)));
            actor.reply(localeManager.getMessage("print.punishment_detail.date", mapOf("date", issued)));
            actor.reply(localeManager.getMessage("print.punishment_detail.status", mapOf("status", (active ? COLOR_ACTIVE : COLOR_INACTIVE) + status)));
            actor.reply(localeManager.getMessage("print.punishment_detail.duration", mapOf("duration", duration)));
            actor.reply(localeManager.getMessage("print.punishment_detail.reason", mapOf("reason", reason)));
            actor.reply(localeManager.getMessage("print.punishment_detail.evidence", mapOf("count", String.valueOf(evidenceCount))));
            actor.reply(localeManager.getMessage("print.punishment_detail.notes", mapOf("count", String.valueOf(notesCount))));
            actor.reply(localeManager.getMessage("print.punishment_detail.modifications", mapOf("count", String.valueOf(modsCount))));
            actor.reply(localeManager.getMessage("print.punishment_detail.footer"));

            if (!CommandUtil.isConsole(actor)) {
                UUID senderUuid = actor.uniqueId();
                platform.runOnMainThread(() ->
                    PluginServices.punishmentActions().sendPunishmentActions(senderUuid, punishmentId));
            }
        }).exceptionally(throwable -> {
            inspectActions.logFailure("#" + punishmentId, throwable);
            CommandUtil.handleException(actor, throwable, localeManager);
            return null;
        });
    }

    private void printLookup(CommandActor actor, String playerQuery) {

        PlayerLookupRequest request = PlayerLookupRequest.builder().query(playerQuery).build();

        httpClientHolder.getClient().lookupPlayer(request).thenAccept(response -> {
            if (response.isSuccess() && response.getData() != null) {
                UUID playerUuid = UUID.fromString(response.getData().getMinecraftUuid());
                resolveOwnNotesThenDisplay(actor, response.getData(), playerUuid, playerQuery);
            } else actor.reply(localeManager.getMessage("general.player_not_found"));
        }).exceptionally(throwable -> {
            CommandUtil.handleException(actor, throwable, localeManager);
            return null;
        });
    }

    private void resolveOwnNotesThenDisplay(CommandActor actor, PlayerLookupResponse.PlayerData data, UUID playerUuid, String playerQuery) {
        StaffProfileLookup.lookupPlayerProfile(httpClientHolder.getClient(), platform, playerQuery).thenAccept(profileResponse -> {
            List<Note> ownNotes = (profileResponse != null && profileResponse.getStatus() == 200)
                    ? profileResponse.getProfile().getNotes() : Collections.emptyList();
            fetchLinkedAndDisplay(actor, data, playerUuid, ownNotes);
        }).exceptionally(profileThrowable -> {
            fetchLinkedAndDisplay(actor, data, playerUuid, Collections.emptyList());
            return null;
        });
    }

    private void fetchLinkedAndDisplay(CommandActor actor, PlayerLookupResponse.PlayerData data, UUID playerUuid, List<Note> targetNotes) {
        httpClientHolder.getClient().getLinkedAccounts(playerUuid)
                .thenAccept(linkedResponse -> displayPlayerInfo(actor, data, linkedResponse, targetNotes))
                .exceptionally(linkedThrowable -> {
                    if (linkedThrowable.getCause() instanceof PanelUnavailableException) actor.reply(localeManager.getMessage("api_errors.panel_unavailable"));
                    else displayPlayerInfo(actor, data, null, targetNotes);
                    return null;
                });
    }

    private void displayPlayerInfo(CommandActor actor, PlayerLookupResponse.PlayerData data,
                                   @Nullable LinkedAccountsResponse linkedResponse, List<Note> targetNotes) {
        String playerName = data.getCurrentUsername() != null ? data.getCurrentUsername() : localeManager.getUnknownWord();

        replyIdentity(actor, data, playerName);
        replyPunishmentStatus(actor, data);
        replyNotesSection(actor, targetNotes);
        replyPunishmentTotal(actor, data);
        replyLinkedAccountsSection(actor, linkedResponse);
        replyTicketTotal(actor, data);
        replyProfileFooter(actor, data, playerName);
    }

    private void replyIdentity(CommandActor actor, PlayerLookupResponse.PlayerData data, String playerName) {
        actor.reply(localeManager.getMessage("print.inspect.header", mapOf("player", playerName)));
        actor.reply(localeManager.getMessage("print.inspect.uuid", mapOf("player", playerName, "uuid", data.getMinecraftUuid())));
    }

    private void replyPunishmentStatus(CommandActor actor, PlayerLookupResponse.PlayerData data) {
        UUID playerUuid = UUID.fromString(data.getMinecraftUuid());
        CachedProfile targetProfile = cache.getPlayerProfile(playerUuid);
        boolean isBanned = targetProfile != null && targetProfile.isBanned();
        boolean isMuted = targetProfile != null && targetProfile.isMuted();

        if (!isBanned || !isMuted) {
            if (data.getRecentPunishments() != null)
                for (PlayerLookupResponse.RecentPunishment punishment : data.getRecentPunishments()) {
                    if (punishment.isActive()) {
                        String type = punishment.getType();
                        if (!isBanned && punishmentTypeCache.isBanType(type)) isBanned = true;
                        if (!isMuted && punishmentTypeCache.isMuteType(type)) isMuted = true;
                    }
                }
        }

        String bannedStatus = yesNoStatus(isBanned);
        String mutedStatus = yesNoStatus(isMuted);
        actor.reply(localeManager.getMessage("print.inspect.currently_banned", mapOf("status", bannedStatus)));
        actor.reply(localeManager.getMessage("print.inspect.currently_muted", mapOf("status", mutedStatus)));
    }

    private static String detailStatusKey(PunishmentDetailResponse.PunishmentDetail detail) {
        Map<String, Object> data = detail.getData() != null ? detail.getData() : Collections.emptyMap();
        if (Boolean.TRUE.equals(data.get("pardoned")) || detail.hasModification(Modification.Type::isPardon)) return STATUS_PARDONED;
        if (detail.hasModification(Modification.Type.ROLLBACK::equals)) return STATUS_ROLLED_BACK;
        if (Boolean.FALSE.equals(data.get("active"))) return STATUS_INACTIVE;
        return STATUS_ACTIVE;
    }

    private String yesNoStatus(boolean value) {
        return (value ? COLOR_YES : COLOR_NO) + localeManager.getYesNoWord(value);
    }

    private void replyNotesSection(CommandActor actor, List<Note> targetNotes) {
        actor.reply(localeManager.getMessage("print.inspect.notes_label"));
        if (targetNotes != null && !targetNotes.isEmpty()) {
            int noteOrdinal = 1;
            for (Note note : targetNotes) {
                if (noteOrdinal > MAX_NOTES_DISPLAYED) break;
                actor.reply(localeManager.getMessage("print.inspect.note_entry", mapOf(
                    "ordinal", String.valueOf(noteOrdinal),
                    "text", NoteDisplay.text(localeManager, note),
                    "issuer", note.issuerNameOr(localeManager.getUnknownWord())
                )));
                noteOrdinal++;
            }
        } else {
            actor.reply(localeManager.getMessage("print.inspect.no_notes"));
        }
    }

    private void replyPunishmentTotal(CommandActor actor, PlayerLookupResponse.PlayerData data) {
        int totalPunishments = 0;
        if (data.getPunishmentStats() != null) totalPunishments = data.getPunishmentStats().getTotalPunishments();
        actor.reply(localeManager.getMessage("print.inspect.total_punishments", mapOf("count", String.valueOf(totalPunishments))));
    }

    private void replyLinkedAccountsSection(CommandActor actor, @Nullable LinkedAccountsResponse linkedResponse) {
        actor.reply(localeManager.getMessage("print.inspect.linked_accounts_label"));
        if (linkedResponse != null && !linkedResponse.getLinkedAccounts().isEmpty()) {
            int accountOrdinal = 1;
            for (Account account : linkedResponse.getLinkedAccounts()) {
                if (accountOrdinal > MAX_LINKED_ACCOUNTS_DISPLAYED) break;
                actor.reply(localeManager.getMessage("print.inspect.linked_account_entry", mapOf(
                    "ordinal", String.valueOf(accountOrdinal),
                    "username", linkedAccountName(account),
                    "status", linkedAccountStatus(account)
                )));
                accountOrdinal++;
            }
            if (linkedResponse.getLinkedAccounts().size() > MAX_LINKED_ACCOUNTS_DISPLAYED)
                actor.reply(localeManager.getMessage("print.inspect.linked_account_more", mapOf(
                    "count", String.valueOf(linkedResponse.getLinkedAccounts().size() - MAX_LINKED_ACCOUNTS_DISPLAYED)
                )));
        } else actor.reply(localeManager.getMessage("print.inspect.no_linked_accounts"));
    }

    private String linkedAccountName(Account account) {
        return !account.getUsernames().isEmpty()
                ? account.getUsernames().get(account.getUsernames().size() - 1).getUsername()
                : localeManager.getUnknownWord();
    }

    private String linkedAccountStatus(Account account) {
        CachedProfile accountProfile = account.getMinecraftUuid() != null ? cache.getPlayerProfile(account.getMinecraftUuid()) : null;
        boolean accountBanned = accountProfile != null && accountProfile.isBanned();
        boolean accountMuted = accountProfile != null && accountProfile.isMuted();

        if (accountBanned && accountMuted) return localeManager.getMessage("player_lookup.status.banned_and_muted");
        if (accountBanned) return localeManager.getMessage("player_lookup.status.banned");
        if (accountMuted) return localeManager.getMessage("player_lookup.status.muted");
        return localeManager.getMessage("player_lookup.status.no_punishments");
    }

    private void replyTicketTotal(CommandActor actor, PlayerLookupResponse.PlayerData data) {
        int totalTickets = 0;
        if (data.getRecentTickets() != null) totalTickets = data.getRecentTickets().size();
        actor.reply(localeManager.getMessage("print.inspect.total_tickets", mapOf("count", String.valueOf(totalTickets))));
    }

    private void replyProfileFooter(CommandActor actor, PlayerLookupResponse.PlayerData data, String playerName) {
        actor.reply(localeManager.getMessage("print.inspect.footer"));

        if (data.getMinecraftUuid() != null) {
            String profileUrl = panelUrl.playerProfile(data.getMinecraftUuid());

            if (!CommandUtil.isConsole(actor)) {
                String profileMessage = ClickableJsonMessage.empty()
                        .extra(ClickableJsonMessage.text("  ").color("gold"))
                        .extra(ClickableJsonMessage.text(localeManager.getMessage("print.inspect.web_profile_link"))
                                .color("aqua")
                                .underlined(true)
                                .openUrl(profileUrl)
                                .hoverText(localeManager.getMessage("print.inspect.web_profile_hover", mapOf("player", playerName))))
                        .toJson();
                UUID senderUuid = actor.uniqueId();
                platform.runOnMainThread(() -> platform.sendJsonMessage(senderUuid, profileMessage));
            } else {
                actor.reply(localeManager.getMessage("print.inspect.profile_fallback", mapOf("url", profileUrl)));
            }
        }
    }

    private String getPunishmentTypeName(int typeOrdinal) {
        return punishmentTypeCache.getNameByOrdinal(typeOrdinal);
    }

}

