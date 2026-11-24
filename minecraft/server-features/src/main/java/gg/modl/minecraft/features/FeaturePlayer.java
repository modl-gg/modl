package gg.modl.minecraft.features;

import java.util.UUID;

public interface FeaturePlayer {

    UUID getUniqueId();

    String getName();

    void sendMessage(String message);

    boolean isOnline();

    int getEntityId();

    double getX();

    double getY();

    double getZ();

    float getYaw();

    float getPitch();

    String getWorldName();
}
