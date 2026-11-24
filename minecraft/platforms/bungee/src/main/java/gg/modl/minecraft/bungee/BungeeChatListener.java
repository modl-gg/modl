package gg.modl.minecraft.bungee;

import gg.modl.minecraft.core.AsyncCommandExecutor;
import gg.modl.minecraft.core.chat.ChatService;
import gg.modl.minecraft.core.chat.CommandInterceptService;
import gg.modl.minecraft.core.service.ChatCommandLogService;
import gg.modl.minecraft.core.util.StringUtil;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ChatEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

@RequiredArgsConstructor
public final class BungeeChatListener implements Listener {
    private static final String COMMAND_PREFIX = "/";
    private static final String MODL_NAMESPACE = "modl:";

    private final ChatService chatService;
    private final CommandInterceptService commandInterceptService;
    private final ChatCommandLogService chatCommandLogService;
    private final AsyncCommandExecutor asyncExecutor;
    private final ProxyServer proxy;

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(ChatEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer)) return;
        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        if (event.isCommand()) {
            gateCommand(event, player);
        } else {
            gateChat(event, player);
        }
    }

    @EventHandler(priority = Byte.MAX_VALUE)
    public void onChatDelivered(ChatEvent event) {
        if (!(event.getSender() instanceof ProxiedPlayer)) return;
        ProxiedPlayer player = (ProxiedPlayer) event.getSender();
        if (event.isCommand()) {
            chatCommandLogService.addCommand(player.getUniqueId().toString(), player.getName(), event.getMessage(),
                    serverNameOf(player));
        } else {
            chatService.recordChat(player.getUniqueId(), player.getName(), event.getMessage(), serverNameOf(player),
                    event.isCancelled());
        }
    }

    private void gateChat(ChatEvent event, ProxiedPlayer player) {
        ChatService.Result result = chatService.handleChat(player.getUniqueId(), player.getName(), event.getMessage(),
                serverNameOf(player), event.isCancelled(), message -> reply(player, message));
        if (result == ChatService.Result.CANCELLED) event.setCancelled(true);
    }

    private void gateCommand(ChatEvent event, ProxiedPlayer player) {
        if (event.isCancelled()) return;
        CommandInterceptService.CommandResult result = commandInterceptService.check(player.getUniqueId(), event.getMessage());
        if (result != CommandInterceptService.CommandResult.ALLOWED) {
            event.setCancelled(true);
            reply(player, commandInterceptService.getBlockMessage(result, player.getUniqueId()));
            return;
        }
        String commandLine = event.getMessage().substring(COMMAND_PREFIX.length()).trim();
        if (isAsyncModlCommand(commandLine)) {
            event.setCancelled(true);
            asyncExecutor.execute(() -> proxy.getPluginManager().dispatchCommand(player, commandLine));
        }
    }

    private boolean isAsyncModlCommand(String commandLine) {
        if (commandLine.isEmpty()) return false;
        String label = commandLine.split("\\s")[0];
        return asyncExecutor.isAsyncCommand(label) || asyncExecutor.isAsyncCommand(label.replace(MODL_NAMESPACE, ""));
    }

    private static void reply(ProxiedPlayer player, String message) {
        player.sendMessage(Colors.get(StringUtil.unescapeNewlines(message)));
    }

    private static String serverNameOf(ProxiedPlayer player) {
        return player.getServer() != null ? player.getServer().getInfo().getName() : "unknown";
    }
}
