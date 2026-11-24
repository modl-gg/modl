package gg.modl.minecraft.api.http.request;

import lombok.Value;

@Value
public class StartImportJobRequest {
    String type, serverName, scopedPunishments, ipHistory;
}
