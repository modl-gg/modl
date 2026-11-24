package gg.modl.backend.audit.repository;

import gg.modl.backend.player.repository.PunishmentRecord;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface StaffActivityRepository {
    List<StaffActionSummary> summarizeLogActivityBySource(Instant startDate);

    Map<String, Long> countTicketResponsesByStaff(Instant startDate);

    Map<String, Integer> averageResponseMinutesByStaff(Instant startDate);

    int averageResponseMinutesOf(String username, Instant startDate);

    List<PunishmentRecord> findPunishmentDetails(Collection<String> usernames, @Nullable String staffId, Instant startDate);

    List<StaffTicketDetail> findTicketDetails(String username, Instant startDate);

    Map<Instant, Long> countPunishmentsByDay(Collection<String> usernames, @Nullable String staffId, Instant startDate);

    Map<Instant, Long> countTicketResponsesByDay(String username, Instant startDate);

    Map<Integer, Long> countPunishmentsByType(Collection<String> usernames, @Nullable String staffId, Instant startDate);
}
