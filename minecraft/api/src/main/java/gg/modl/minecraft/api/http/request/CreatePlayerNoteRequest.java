package gg.modl.minecraft.api.http.request;

import gg.modl.minecraft.api.PlayerNoteKind;
import lombok.Value;
import org.jspecify.annotations.Nullable;

@Value
public class CreatePlayerNoteRequest {
    transient String targetUuid;
    @Nullable String issuerName;
    transient ActionIssuer issuer;
    PlayerNoteKind kind;
    String text;
}
