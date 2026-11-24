package gg.modl.minecraft.api.http.request;

import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class ChangePunishmentDurationRequest {
    String punishmentId, issuerName;
    ActionIssuer issuer;
    @Nullable Long newDuration;
}
