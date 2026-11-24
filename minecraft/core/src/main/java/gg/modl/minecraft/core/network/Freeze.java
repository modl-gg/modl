package gg.modl.minecraft.core.network;

import lombok.Value;

import java.util.UUID;

@Value
public class Freeze {
    UUID staff;
    long frozenAtMillis;
}
