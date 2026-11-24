package gg.modl.minecraft.core.connection;

import lombok.Builder;
import lombok.ToString;
import lombok.Value;

@Value
@Builder
public class DatabaseSettings {
    @ToString.Exclude @Builder.Default String url = "";
    @Builder.Default String username = "";
    @ToString.Exclude @Builder.Default String password = "";
    @Builder.Default String sqliteNativeDir = "";
}
