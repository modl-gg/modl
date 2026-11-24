package gg.modl.minecraft.features.staffmode.custody;

import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

public interface BelongingsPort {
    String markKind();

    Optional<UUID> custodyMark(UUID player);

    void clearMark(UUID player);

    boolean isAlive(UUID player);

    boolean hasSavedPlayerData(UUID player);

    Belongings capture(UUID player) throws BelongingsException;

    Belongings decode(String encoded) throws BelongingsException;

    boolean isUnchanged(UUID player, Belongings captured);

    void takeAway(UUID player, UUID custody) throws BelongingsException;

    void giveBack(UUID player, Belongings belongings) throws BelongingsException;

    void leaveCreative(UUID player, @Nullable String recordedGameMode);
}
