package gg.modl.minecraft.api.http;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Getter @NoArgsConstructor @AllArgsConstructor
public class ChatLogEntry {
    private String uuid;
    private @Nullable String username;
    private String message, server;
    private long timestamp;
}
