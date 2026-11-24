package gg.modl.minecraft.core.login;

import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

@Value
public class LoginAttempt {
    UUID uuid;
    String username;
    String ipAddress;
    String serverName;
    @Nullable String nativeTexture;
}
