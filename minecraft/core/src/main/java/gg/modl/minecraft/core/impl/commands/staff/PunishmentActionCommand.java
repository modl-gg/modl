package gg.modl.minecraft.core.impl.commands.staff;

import gg.modl.minecraft.core.connection.PanelUrl;
import dev.simplix.cirrus.player.CirrusPlayerWrapper;
import gg.modl.minecraft.api.Account;
import gg.modl.minecraft.api.Punishment;
import gg.modl.minecraft.api.http.request.AddPunishmentEvidenceRequest;
import gg.modl.minecraft.api.http.response.PunishmentDetailResponse;
import gg.modl.minecraft.core.HttpClientHolder;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.command.StaffOnly;
import gg.modl.minecraft.core.impl.menus.inspect.ModifyPunishmentMenu;
import gg.modl.minecraft.core.impl.menus.util.StaffViewer;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.staff.action.AuthorizedStaff;
import gg.modl.minecraft.core.staff.action.StaffRequirements;
import gg.modl.minecraft.core.util.ApiFailures;
import gg.modl.minecraft.core.util.ClickableJsonMessage;
import gg.modl.minecraft.core.util.CommandUtil;
import lombok.RequiredArgsConstructor;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.command.CommandActor;

import java.util.UUID;
import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

@RequiredArgsConstructor
public class PunishmentActionCommand {
    private final HttpClientHolder httpClientHolder;
    private final Platform platform;
    private final Cache cache;
    private final LocaleManager localeManager;
    private final PanelUrl panelUrl;

    @Command("punishment_action")
    @StaffOnly
    public void punishmentAction(CommandActor actor, String action, String punishmentId) {
        if (CommandUtil.isConsole(actor)) {
            actor.reply(localeManager.getMessage("general.gui_requires_player"));
            return;
        }

        if ("modify".equals(action)) {
            if (!platform.areMenusAvailable()) {
                actor.reply(localeManager.getMessage("general.gui_unavailable"));
                return;
            }
            openModifyMenu(actor, punishmentId);
        } else if ("link-evidence".equals(action)) {
            promptLinkEvidence(actor, punishmentId);
        } else if ("upload-evidence".equals(action)) {
            openUploadPage(actor, punishmentId);
        } else {
            actor.reply(localeManager.getMessage("punishment_action.unknown_action", mapOf("action", action)));
        }
    }

    private void openModifyMenu(CommandActor actor, String punishmentId) {
        UUID senderUuid = actor.uniqueId();

        httpClientHolder.getClient().getPunishmentDetail(punishmentId).thenAccept(response -> {
            if (!response.isSuccess() || response.getPunishment() == null) {
                actor.reply(localeManager.getMessage("print.punishment_detail.not_found", mapOf("id", punishmentId)));
                return;
            }

            PunishmentDetailResponse.PunishmentDetail detail = response.getPunishment();
            UUID playerUuid = UUID.fromString(detail.getPlayerUuid());

            httpClientHolder.getClient().getPlayerProfile(playerUuid).thenAccept(profileResponse -> {
                if (profileResponse.getStatus() != 200) {
                    actor.reply(localeManager.getMessage("general.player_not_found"));
                    return;
                }

                Account account = profileResponse.getProfile();
                Punishment punishment = null;
                for (Punishment p : account.getPunishments())
                    if (p.getId().equals(punishmentId)) {
                        punishment = p;
                        break;
                    }

                if (punishment == null) {
                    actor.reply(localeManager.getMessage("print.punishment_detail.not_found", mapOf("id", punishmentId)));
                    return;
                }

                String senderName = CommandUtil.resolveSenderName(senderUuid, cache, platform);

                ModifyPunishmentMenu menu = new ModifyPunishmentMenu(
                    platform, httpClientHolder.getClient(), senderUuid, senderName,
                    account, punishment, null, null
                );

                CirrusPlayerWrapper player = platform.getPlayerWrapper(senderUuid);
                if (player != null) menu.display(player);
            }).exceptionally(throwable -> {
                actor.reply(localeManager.getMessage("api_errors.panel_unavailable"));
                return null;
            });
        }).exceptionally(throwable -> {
            actor.reply(localeManager.getMessage("api_errors.panel_unavailable"));
            return null;
        });
    }

    private void promptLinkEvidence(CommandActor actor, String punishmentId) {
        UUID senderUuid = actor.uniqueId();
        String senderName = CommandUtil.resolveSenderName(senderUuid, cache, platform);
        new StaffViewer(platform, senderUuid).prompt(StaffRequirements.MANAGE_PUNISHMENT_EVIDENCE,
                localeManager.getMessage("punishment_action.enter_evidence_url", mapOf("id", punishmentId)),
                (issuer, url) -> {
                    if (url == null || url.trim().isEmpty()) {
                        platform.sendMessage(senderUuid, localeManager.getMessage("punishment_action.no_url"));
                        return;
                    }

                    AddPunishmentEvidenceRequest request = new AddPunishmentEvidenceRequest(
                            punishmentId, senderName, issuer.actionIssuer(), url
                    );

                    httpClientHolder.getClient().addPunishmentEvidence(request).thenAccept(v -> platform.sendMessage(senderUuid, localeManager.getMessage("punishment_action.evidence_linked", mapOf("id", punishmentId)))).exceptionally(throwable -> {
                        platform.sendMessage(senderUuid,
                                ApiFailures.describe(localeManager, "punishment_action.evidence_link_failed", throwable));
                        return null;
                    });
                },
                () -> platform.sendMessage(senderUuid, localeManager.getMessage("punishment_action.evidence_cancelled"))
        );
    }

    private void openUploadPage(CommandActor actor, String punishmentId) {
        UUID senderUuid = actor.uniqueId();
        new StaffViewer(platform, senderUuid).authorizeAs(StaffRequirements.MANAGE_PUNISHMENT_EVIDENCE)
                .ifPresent(issuer -> requestUploadPage(actor, punishmentId, issuer));
    }

    private void requestUploadPage(CommandActor actor, String punishmentId, AuthorizedStaff issuer) {
        UUID senderUuid = actor.uniqueId();
        String senderName = CommandUtil.resolveSenderName(senderUuid, cache, platform);

        actor.reply(localeManager.getMessage("punishment_action.generating_upload"));

        httpClientHolder.getClient().createEvidenceUploadToken(punishmentId, senderName, issuer.actionIssuer()).thenAccept(response -> {
            if (!response.isSuccess() || response.getToken() == null) {
                actor.reply(localeManager.getMessage("punishment_action.upload_failed"));
                return;
            }

            String uploadUrl = panelUrl.evidenceUpload(response.getToken());
            String json = ClickableJsonMessage.empty()
                    .extra(ClickableJsonMessage.text(localeManager.getMessage("punishment_action.upload_link"))
                            .color("green")
                            .underlined(true)
                            .bold(true)
                            .openUrl(uploadUrl)
                            .hoverText(localeManager.getMessage("punishment_action.upload_link_hover")))
                    .toJson();

            platform.runOnMainThread(() -> platform.sendJsonMessage(senderUuid, json));
        }).exceptionally(throwable -> {
            actor.reply(localeManager.getMessage("punishment_action.upload_failed"));
            return null;
        });
    }
}

