package gg.modl.minecraft.api.http.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter @NoArgsConstructor @AllArgsConstructor @ToString
public class PanelLoginLinkResponse {
    @ToString.Exclude private String url;
    private long expiresInSeconds;
}
