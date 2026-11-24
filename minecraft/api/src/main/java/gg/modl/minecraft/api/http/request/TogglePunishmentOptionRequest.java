package gg.modl.minecraft.api.http.request;

import lombok.Value;

@Value
public class TogglePunishmentOptionRequest {
    String punishmentId, issuerName;
    ActionIssuer issuer;
    String option;
    boolean enabled;
}
