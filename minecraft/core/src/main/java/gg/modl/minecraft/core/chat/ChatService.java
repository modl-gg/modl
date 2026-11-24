package gg.modl.minecraft.core.chat;

import gg.modl.minecraft.core.PluginServices;
import gg.modl.minecraft.core.Platform;
import gg.modl.minecraft.core.cache.Cache;
import gg.modl.minecraft.core.cache.CachedProfile;
import gg.modl.minecraft.core.config.ConfigManager.StaffChatConfig;
import gg.modl.minecraft.core.locale.LocaleManager;
import gg.modl.minecraft.core.punishment.PunishmentMessageService;
import gg.modl.minecraft.core.service.ChatCommandLogService;
import gg.modl.minecraft.core.service.ChatManagementService;
import gg.modl.minecraft.core.service.ChatMessageCache;
import gg.modl.minecraft.core.network.ModerationSessionService;
import gg.modl.minecraft.core.service.NetworkChatInterceptService;
import gg.modl.minecraft.core.service.StaffChatService;
import gg.modl.minecraft.core.service.StaffChatService.ChatMode;
import gg.modl.minecraft.core.staff.PermissionUtil;
import gg.modl.minecraft.core.staff.action.StaffActionDenied;
import gg.modl.minecraft.core.staff.action.StaffActionGate;
import gg.modl.minecraft.core.staff.action.StaffActors;
import gg.modl.minecraft.core.staff.action.StaffRequirements;

import java.util.UUID;
import java.util.function.Consumer;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class ChatService {
    public enum Result {
        CANCELLED,
        ALLOWED
    }

    private final Platform platform;
    private final Cache cache;
    private final LocaleManager localeManager;
    private final ChatMessageCache chatMessageCache;
    private final StaffChatService staffChatService;
    private final StaffChatConfig staffChatConfig;
    private final ChatManagementService chatManagementService;
    private final ModerationSessionService moderation;
    private final ChatCommandLogService chatCommandLogService;
    private final NetworkChatInterceptService networkChatInterceptService;
    private final PunishmentMessageService punishmentMessageService;
    private final StaffActionGate staffActionGate;

    public ChatService(Platform platform, Cache cache, LocaleManager localeManager, ChatMessageCache chatMessageCache,
                       StaffChatService staffChatService, StaffChatConfig staffChatConfig,
                       ChatManagementService chatManagementService, ModerationSessionService moderation,
                       ChatCommandLogService chatCommandLogService, NetworkChatInterceptService networkChatInterceptService,
                       PunishmentMessageService punishmentMessageService, StaffActionGate staffActionGate) {
        this.platform = platform;
        this.cache = cache;
        this.localeManager = localeManager;
        this.chatMessageCache = chatMessageCache;
        this.staffChatService = staffChatService;
        this.staffChatConfig = staffChatConfig;
        this.chatManagementService = chatManagementService;
        this.moderation = moderation;
        this.chatCommandLogService = chatCommandLogService;
        this.networkChatInterceptService = networkChatInterceptService;
        this.punishmentMessageService = punishmentMessageService;
        this.staffActionGate = staffActionGate;
    }

    public Result handleChat(UUID senderUuid, String senderName, String message, String serverName,
                             boolean alreadyCancelled, Consumer<String> sendMessage) {
        if (alreadyCancelled) return Result.CANCELLED;
        if (PluginServices.chatInput().handleChat(senderUuid, message)) return Result.CANCELLED;

        chatMessageCache.updatePlayerServer(serverName, senderUuid.toString());

        if (staffChatService.isInStaffChat(senderUuid)) {
            postFromStaffChatMode(senderUuid, senderName, message, sendMessage);
            return Result.CANCELLED;
        }

        boolean isStaff = PermissionUtil.isStaff(senderUuid, cache);
        if (isStaff && staffChatConfig.isEnabled() && message.startsWith(staffChatConfig.getPrefix())) {
            postWithPrefix(senderUuid, senderName, message, sendMessage);
            return Result.CANCELLED;
        }

        if (!chatManagementService.canSendMessage(senderUuid, isStaff)) {
            if (!chatManagementService.isChatEnabled()) {
                sendMessage.accept(localeManager.getMessage("chat_management.chat_disabled"));
            } else {
                sendSlowModeWait(senderUuid, sendMessage);
            }
            return Result.CANCELLED;
        }

        CachedProfile senderProfile = cache.getPlayerProfile(senderUuid);
        if (senderProfile != null && senderProfile.isMuted()) {
            sendMessage.accept(punishmentMessageService.getMuteMessage(senderProfile.getActiveMute()));
            return Result.CANCELLED;
        }

        if (moderation.isFrozen(senderUuid)) {
            String frozenChat = localeManager.getMessage("freeze.frozen_chat",
                    mapOf("player", senderName, "message", message));
            platform.staffBroadcast(frozenChat);
            sendMessage.accept(frozenChat);
            return Result.CANCELLED;
        }

        if (!isStaff && !chatManagementService.recordMessageSent(senderUuid)) {
            sendSlowModeWait(senderUuid, sendMessage);
            return Result.CANCELLED;
        }

        return Result.ALLOWED;
    }

    public void recordChat(UUID senderUuid, String senderName, String message, String serverName, boolean cancelled) {
        if (cancelled) return;

        chatMessageCache.addMessage(serverName, senderUuid.toString(), senderName, message);
        chatCommandLogService.addChatMessage(senderUuid.toString(), senderName, message, serverName);

        for (UUID interceptor : networkChatInterceptService.getInterceptors()) {
            if (interceptor.equals(senderUuid)) continue;
            if (!staffActionGate.allows(StaffActors.player(interceptor, platform), StaffRequirements.INTERCEPT_CHAT)) continue;
            platform.sendMessage(interceptor, localeManager.getMessage("intercept.message",
                    mapOf("player", senderName, "message", message)));
        }
    }

    private void postFromStaffChatMode(UUID senderUuid, String senderName, String message, Consumer<String> sendMessage) {
        try {
            staffActionGate.require(StaffActors.player(senderUuid, platform), StaffRequirements.STAFF);
        } catch (StaffActionDenied denied) {
            staffChatService.setMode(senderUuid, ChatMode.NORMAL);
            sendMessage.accept(localeManager.getMessage("staff_chat.mode_lost"));
            return;
        }
        postToStaffChat(senderUuid, senderName, message);
    }

    private void postWithPrefix(UUID senderUuid, String senderName, String message, Consumer<String> sendMessage) {
        try {
            staffActionGate.require(StaffActors.player(senderUuid, platform), StaffRequirements.STAFF);
        } catch (StaffActionDenied denied) {
            sendMessage.accept(denied.getMessage());
            return;
        }
        String unprefixed = message.substring(staffChatConfig.getPrefix().length()).trim();
        if (!unprefixed.isEmpty()) postToStaffChat(senderUuid, senderName, unprefixed);
    }

    private void postToStaffChat(UUID senderUuid, String senderName, String message) {
        String panelName = cache.getStaffDisplayName(senderUuid);
        platform.staffBroadcast(staffChatConfig.formatMessage(senderName, panelName, message));
    }

    private void sendSlowModeWait(UUID senderUuid, Consumer<String> sendMessage) {
        int remaining = chatManagementService.getSlowModeRemaining(senderUuid);
        sendMessage.accept(localeManager.getMessage("chat_management.slow_mode_wait",
                mapOf("seconds", String.valueOf(remaining))));
    }
}
