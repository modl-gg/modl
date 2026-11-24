package gg.modl.minecraft.core.connection;

import lombok.Builder;
import lombok.ToString;
import lombok.Value;

@Value
@Builder
public class SmtpSettings {
    public static final int DEFAULT_PORT = 587;

    @Builder.Default String host = "";
    @Builder.Default int port = DEFAULT_PORT;
    @Builder.Default String username = "";
    @ToString.Exclude @Builder.Default String password = "";
    @Builder.Default String from = "";
    @Builder.Default boolean starttls = true;
}
