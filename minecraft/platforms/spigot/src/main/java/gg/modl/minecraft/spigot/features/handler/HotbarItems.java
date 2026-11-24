package gg.modl.minecraft.spigot.features.handler;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import dev.simplix.cirrus.common.packet.LegacyItemMapping;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.inventory.ItemStack;

import java.util.logging.Logger;

final class HotbarItems {
    private final Logger logger;
    private final ClientVersion serverVersion;

    HotbarItems(Logger logger, ClientVersion serverVersion) {
        this.logger = logger;
        this.serverVersion = serverVersion;
    }

    ItemStack create(String itemId) {
        return SpigotConversionUtil.toBukkitItemStack(LegacyItemMapping.resolve(itemId, serverVersion,
                warning -> logger.warning("[staff-mode] " + warning)).amount(1).build());
    }
}
