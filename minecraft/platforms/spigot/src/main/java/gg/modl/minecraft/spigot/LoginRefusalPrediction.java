package gg.modl.minecraft.spigot;

import java.net.InetAddress;
import java.util.UUID;

interface LoginRefusalPrediction {
    boolean refuses(UUID uuid, InetAddress address);
}
