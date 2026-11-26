package gg.modl.minecraft.replay.recording;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import lombok.Getter;

@Getter
final class TranslationContext {
    private final ServerVersion serverVersion;
    private final ClientVersion version;
    private final NameTable names;
    private final ItemConverter items;
    private final MetadataTranslator metadata;
    private final int playerType;

    TranslationContext(ServerVersion serverVersion, NameTable names) {
        this.serverVersion = serverVersion;
        this.version = serverVersion.toClientVersion();
        this.names = names;
        this.items = new ItemConverter(names, serverVersion);
        this.metadata = new MetadataTranslator(items, serverVersion);
        this.playerType = names.intern(EntityTypes.PLAYER.getName().toString());
    }

    int typeName(EntityType type) {
        return names.id(type, () -> type.getName().toString());
    }

    static boolean isLiving(EntityType type) {
        return type.isInstanceOf(EntityTypes.LIVINGENTITY);
    }

    static boolean isPlayer(EntityType type) {
        return type.isInstanceOf(EntityTypes.PLAYER);
    }
}
