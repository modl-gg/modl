package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class LinkedBanExemptions {
    private static final Set<PunishmentModificationType> STAFF_ENDINGS = EnumSet.of(
        PunishmentModificationType.MANUAL_PARDON,
        PunishmentModificationType.APPEAL_ACCEPT,
        PunishmentModificationType.ROLLBACK);

    private final Set<String> exemptParentIds;

    private LinkedBanExemptions(Set<String> exemptParentIds) {
        this.exemptParentIds = exemptParentIds;
    }

    static LinkedBanExemptions of(List<Punishment> linkedBans, PlayerStatusCalculator statusCalculator) {
        return new LinkedBanExemptions(linkedBans.stream()
            .filter(linkedBan -> linkedBan.getTypeOrdinal() == Punishment.LINKED_BAN_TYPE_ORDINAL)
            .filter(linkedBan -> statusCalculator.isPunishmentActiveOrUnstarted(linkedBan) || endedByStaff(linkedBan))
            .map(linkedBan -> linkedBan.data().linkedBanId())
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet()));
    }

    boolean covers(String parentId) {
        return exemptParentIds.contains(parentId);
    }

    private static boolean endedByStaff(Punishment linkedBan) {
        return linkedBan.getModifications().stream()
            .map(modification -> PunishmentModificationType.fromName(modification.type()))
            .anyMatch(STAFF_ENDINGS::contains);
    }
}
