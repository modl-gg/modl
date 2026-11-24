package gg.modl.backend.player.service;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.player.repository.IssuerCount;
import gg.modl.backend.staff.data.Staff;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class StaffIssuedPunishments {
    private final Map<String, Long> countsByName;

    private StaffIssuedPunishments(Map<String, Long> countsByName) {
        this.countsByName = countsByName;
    }

    public static StaffIssuedPunishments of(List<Staff> staff, List<IssuerCount> issuerCounts) {
        Map<String, String> namesById = staff.stream()
            .filter(member -> member.getId() != null && member.getUsername() != null)
            .collect(Collectors.toMap(Staff::getId, Staff::getUsername, (first, second) -> first));
        Map<String, Long> counts = new HashMap<>();
        for (IssuerCount count : issuerCounts) {
            IssuerAttribution attribution = IssuerAttribution.of(count.kind(), count.issuerId(), count.issuerName(), namesById);
            if (attribution.kind() == PunishmentIssuerKind.STAFF) {
                counts.merge(normalized(attribution.name()), count.count(), Long::sum);
            }
        }
        return new StaffIssuedPunishments(counts);
    }

    public long issuedBy(Staff member) {
        return Stream.of(member.getUsername(), member.getAssignedMinecraftUsername())
            .filter(Objects::nonNull)
            .filter(name -> !name.isBlank())
            .map(StaffIssuedPunishments::normalized)
            .distinct()
            .mapToLong(name -> countsByName.getOrDefault(name, 0L))
            .sum();
    }

    private static String normalized(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
