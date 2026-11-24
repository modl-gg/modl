package gg.modl.backend.player.data;

import gg.modl.backend.player.data.punishment.Punishment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Player {
    private String id;

    private UUID minecraftUuid;

    @Builder.Default
    private List<UsernameEntry> usernames = new ArrayList<>();

    @Builder.Default
    private List<NoteEntry> notes = new ArrayList<>();

    @Builder.Default
    private List<IPEntry> ipAddresses = new ArrayList<>();

    @Builder.Default
    private List<Punishment> punishments = new ArrayList<>();

    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Map<String, Object> data = new HashMap<>();

    public PlayerDataView data() {
        return PlayerDataView.ownedBy(data, map -> data = map);
    }

    public void replaceData(Map<String, Object> replacement) {
        this.data = replacement;
    }
}
