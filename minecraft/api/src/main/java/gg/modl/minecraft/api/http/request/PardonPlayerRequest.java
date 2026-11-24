package gg.modl.minecraft.api.http.request;

import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class PardonPlayerRequest {
    String playerName;
    @Nullable String issuerName;
    ActionIssuer issuer;
    @Nullable String punishmentType, reason;
}
