package gg.modl.minecraft.api.http.request;

import com.google.gson.annotations.SerializedName;
import lombok.Value;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

@Value
public class PunishmentCreateRequest {
    String targetUuid, issuerName;
    transient ActionIssuer issuer;
    @Nullable String reason;
    @Nullable String severity, status;
    @SerializedName("type_ordinal") int typeOrdinal;
    @Nullable Long duration;
    Map<String, Object> data;
    List<String> notes, attachedTicketIds;
}
