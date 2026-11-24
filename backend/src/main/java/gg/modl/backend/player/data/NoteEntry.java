package gg.modl.backend.player.data;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NoteEntry {
    private String id;

    private String text;
    private Instant date;
    private @Nullable String issuerName;
    private @Nullable String issuerId;
    private PlayerNoteKind kind;
}
