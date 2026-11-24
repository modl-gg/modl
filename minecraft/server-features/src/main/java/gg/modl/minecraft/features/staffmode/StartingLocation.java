package gg.modl.minecraft.features.staffmode;

import lombok.Value;

import java.util.UUID;

@Value
public class StartingLocation {
    UUID worldId;
    double x;
    double y;
    double z;
    float yaw;
    float pitch;
}
