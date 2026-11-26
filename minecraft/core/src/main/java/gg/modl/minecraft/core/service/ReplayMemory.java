package gg.modl.minecraft.core.service;

import lombok.Value;

@Value
public class ReplayMemory {
    long usedBytes;
    long limitBytes;
    int recordings;
}
