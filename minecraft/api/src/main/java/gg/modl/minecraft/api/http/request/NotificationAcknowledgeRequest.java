package gg.modl.minecraft.api.http.request;

import lombok.Value;

import java.util.List;

@Value
public class NotificationAcknowledgeRequest {
    String playerUuid, acknowledgedAt;
    List<String> notificationIds;
}
