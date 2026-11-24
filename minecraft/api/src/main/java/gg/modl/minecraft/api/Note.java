package gg.modl.minecraft.api;

import com.google.gson.annotations.SerializedName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

@Getter @NoArgsConstructor @AllArgsConstructor
public class Note {
    private @SerializedName("text") String text;
    private @Nullable @SerializedName("date") Instant date;
    private @Nullable @SerializedName("issuerName") String issuerName;
    private @Nullable @SerializedName("issuerId") String issuerId;
    private @Nullable @SerializedName("kind") PlayerNoteKind kind;

    public Note(String text, @Nullable Instant date, @Nullable String issuerName, @Nullable String issuerId) {
        this(text, date, issuerName, issuerId, PlayerNoteKind.NOTE);
    }

    public String issuerNameOr(String fallback) {
        return issuerName != null ? issuerName : fallback;
    }

    public PlayerNoteKind getKind() {
        return kind != null ? kind : PlayerNoteKind.NOTE;
    }

    public boolean isWarning() {
        return getKind() == PlayerNoteKind.WARNING;
    }
}
