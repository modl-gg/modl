package gg.modl.minecraft.spigot;

import gg.modl.minecraft.core.util.PluginLogger;
import gg.modl.minecraft.core.util.PublicMethods;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class SpigotCommandNames {
    private final @Nullable CommandMap commandMap;

    private SpigotCommandNames(@Nullable CommandMap commandMap) {
        this.commandMap = commandMap;
    }

    static SpigotCommandNames detect(@Nullable Server server, PluginLogger logger) {
        if (server != null) {
            for (Method accessor : PublicMethods.withoutParameters(server.getClass(), "getCommandMap")) {
                if (!CommandMap.class.isAssignableFrom(accessor.getReturnType())) continue;
                try {
                    return new SpigotCommandNames((CommandMap) accessor.invoke(server));
                } catch (ReflectiveOperationException | RuntimeException unavailable) {
                    logger.debug("The server's command map is not readable: " + unavailable);
                }
            }
        }
        logger.debug("The server does not expose its command map; muted and unlogged commands match by name only");
        return new SpigotCommandNames(null);
    }

    Collection<String> of(String label) {
        Command command = commandMap == null ? null : commandMap.getCommand(label);
        if (command == null) return Collections.singleton(label);
        Set<String> names = new LinkedHashSet<>();
        names.add(label);
        names.add(command.getName());
        names.add(command.getLabel());
        names.addAll(command.getAliases());
        return Collections.unmodifiableSet(names);
    }
}
