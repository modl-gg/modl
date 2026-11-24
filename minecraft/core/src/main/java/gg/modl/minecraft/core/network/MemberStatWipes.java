package gg.modl.minecraft.core.network;

import java.util.UUID;

public interface MemberStatWipes {
    boolean execute(String username, UUID uuid, String punishmentId);
}
