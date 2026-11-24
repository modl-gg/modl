package gg.modl.backend.player.data.punishment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Punishment {
    public static final int LINKED_BAN_TYPE_ORDINAL = 4;

    private String id;

    private int typeOrdinal;

    @Nullable
    private String issuerName;

    @Nullable
    private String issuerId;

    private PunishmentIssuerKind issuerKind = PunishmentIssuerKind.UNKNOWN;

    private Instant issued;

    @Nullable
    private Instant started;

    private List<PunishmentModification> modifications = new ArrayList<>();

    private List<PunishmentNote> notes = new ArrayList<>();

    private List<PunishmentEvidence> evidence = new ArrayList<>();

    private List<String> attachedTicketIds = new ArrayList<>();

    @Nullable
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Map<String, Object> data = new HashMap<>();

    public PunishmentDataView data() {
        return PunishmentDataView.ownedBy(data, map -> data = map);
    }

    public ResolvedCategory enforcementCategory() {
        return data().enforcementCategory();
    }

    public void replaceData(Map<String, Object> replacement) {
        this.data = replacement;
    }

    public boolean isPardoned() {
        return modifications.stream()
            .anyMatch(modification -> PunishmentModificationType.isPardon(modification.type()));
    }

    public boolean hasRollback() {
        return modifications.stream()
            .anyMatch(modification -> PunishmentModificationType.fromName(modification.type()) == PunishmentModificationType.ROLLBACK);
    }

    public boolean hasEndedEnforcement() {
        return modifications.stream()
            .anyMatch(modification -> PunishmentModificationType.endsEnforcement(modification.type()));
    }

    public boolean isUnstarted() {
        return data().isUnstarted() && !hasEndedEnforcement();
    }

    public boolean hasUnstartedStatus() {
        return data().isUnstarted();
    }
}
