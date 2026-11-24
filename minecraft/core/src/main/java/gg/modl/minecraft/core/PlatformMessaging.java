package gg.modl.minecraft.core;

import java.util.UUID;

public interface PlatformMessaging {
    void broadcast(String string);
    void staffBroadcast(String string);
    void staffBroadcast(String string, StaffAudience audience);
    void staffJsonBroadcast(String jsonMessage);
    void staffJsonBroadcast(String jsonMessage, StaffAudience audience);
    void sendMessage(UUID uuid, String message);
    void sendJsonMessage(UUID uuid, String jsonMessage);
    void setStaffAudience(StaffAudience staffAudience);
}
