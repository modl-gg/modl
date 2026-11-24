package gg.modl.minecraft.velocity;

import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import gg.modl.minecraft.core.chat.ChatService;
import gg.modl.minecraft.core.chat.CommandInterceptService;
import gg.modl.minecraft.core.service.ChatCommandLogService;
import gg.modl.minecraft.core.util.StringUtil;
import lombok.RequiredArgsConstructor;

import java.util.Objects;

@RequiredArgsConstructor
public class ChatListener {
    private static final short BEFORE_SECURE_CHAT_VERDICTS = Short.MIN_VALUE + 1;

    private final ChatService chatService;
    private final CommandInterceptService commandInterceptService;
    private final ChatCommandLogService chatCommandLogService;

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public void onPlayerChat(PlayerChatEvent event) {
        Player player = event.getPlayer();
        ChatService.Result result = chatService.handleChat(
                player.getUniqueId(), player.getUsername(), event.getMessage(), getPlayerServerName(player),
                !event.getResult().isAllowed(),
                msg -> player.sendMessage(Colors.get(StringUtil.unescapeNewlines(msg))));
        if (result == ChatService.Result.CANCELLED) event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.CUSTOM, priority = BEFORE_SECURE_CHAT_VERDICTS)
    public void onPlayerChatDelivered(PlayerChatEvent event) {
        PlayerChatEvent.ChatResult result = event.getResult();
        chatService.recordChat(event.getPlayer().getUniqueId(), event.getPlayer().getUsername(),
                result.getMessage().orElse(event.getMessage()), getPlayerServerName(event.getPlayer()),
                !result.isAllowed());
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.FIRST)
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player)) return;
        Player player = (Player) event.getCommandSource();

        CommandInterceptService.CommandResult result = commandInterceptService.check(
                player.getUniqueId(), event.getCommand());
        if (result == CommandInterceptService.CommandResult.ALLOWED) return;

        event.setResult(CommandExecuteEvent.CommandResult.denied());
        String blockMessage = StringUtil.unescapeNewlines(
                commandInterceptService.getBlockMessage(result, player.getUniqueId()));
        player.sendMessage(Colors.get(Objects.requireNonNull(blockMessage)));
    }

    @SuppressWarnings("deprecation")
    @Subscribe(order = PostOrder.LAST)
    public void onCommandRan(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player)) return;
        Player player = (Player) event.getCommandSource();
        chatCommandLogService.addCommand(player.getUniqueId().toString(), player.getUsername(),
                "/" + event.getCommand(), getPlayerServerName(player));
    }

    private String getPlayerServerName(Player player) {
        return player.getCurrentServer()
                .map(conn -> conn.getServerInfo().getName())
                .orElse("unknown");
    }
}
