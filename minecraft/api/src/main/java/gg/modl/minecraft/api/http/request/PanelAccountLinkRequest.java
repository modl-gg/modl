package gg.modl.minecraft.api.http.request;

import lombok.Value;

@Value
public class PanelAccountLinkRequest {
    String minecraftUuid;
    String minecraftUsername;
    String code;
}
