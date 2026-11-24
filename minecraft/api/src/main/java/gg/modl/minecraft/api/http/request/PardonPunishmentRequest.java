package gg.modl.minecraft.api.http.request;

import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class PardonPunishmentRequest {
    transient String punishmentId;
    @Nullable String issuerName;
    ActionIssuer issuer;
    @Nullable String reason, expectedType;
}
