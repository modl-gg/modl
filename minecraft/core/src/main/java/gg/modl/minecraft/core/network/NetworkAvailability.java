package gg.modl.minecraft.core.network;

public interface NetworkAvailability {
    NetworkAvailability EVERYWHERE = serverName -> true;
    NetworkAvailability NOWHERE = serverName -> false;

    boolean isEnforcedOn(String serverName);
}
