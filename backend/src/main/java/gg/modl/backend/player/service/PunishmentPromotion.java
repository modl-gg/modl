package gg.modl.backend.player.service;

import gg.modl.backend.database.UnitOfWork;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.repository.PlayerPunishmentLock;
import gg.modl.backend.player.repository.PunishmentRecord;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.server.data.Server;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PunishmentPromotion {
    private static final List<ResolvedCategory> QUEUED_CATEGORIES = List.of(ResolvedCategory.BAN, ResolvedCategory.MUTE);
    private static final Comparator<Punishment> QUEUE_ORDER =
        Comparator.comparing(Punishment::getIssued).thenComparing(Punishment::getId);

    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final PunishmentRepository punishmentRepository;
    private final PlayerPunishmentLock playerLock;
    private final PlayerStatusCalculator statusCalculator;
    private final PunishmentRealtimePublisher realtimePublisher;

    public List<String> promote(Server server, Player loaded) {
        if (promotable(loaded.getPunishments()).isEmpty()) {
            return List.of();
        }
        return promote(server, loaded.getId());
    }

    public List<String> promote(Server server, String playerId) {
        return unitOfWork.write(() -> {
            playerLock.lock(playerId);
            List<PunishmentRecord> promoted = new ArrayList<>();
            for (Punishment candidate : promotable(punishmentRepository.findEnforcementRelevant(playerId, clock.instant()))) {
                if (punishmentRepository.promote(candidate.getId())) {
                    promoted.add(punishmentRepository.findById(candidate.getId())
                        .orElseThrow(() -> new ResourceNotFoundException("Punishment not found")));
                }
            }
            unitOfWork.afterCommit(() -> realtimePublisher.punishmentsPromoted(server, promoted));
            return promoted.stream().map(promotion -> promotion.punishment().getId()).toList();
        });
    }

    public void promoteAfterCommit(Server server, String playerId) {
        unitOfWork.afterCommit(() -> {
            if (punishmentRepository.hasQueuedWhileOnline(playerId)) {
                promote(server, playerId);
            }
        });
    }

    public boolean waitsBehindAnother(List<Punishment> punishments, Punishment target) {
        ResolvedCategory category = statusCalculator.getEffectiveCategory(target);
        return category.isEnforced() && punishments.stream()
            .filter(other -> !other.getId().equals(target.getId()))
            .filter(other -> statusCalculator.getEffectiveCategory(other) == category)
            .anyMatch(other -> statusCalculator.isPunishmentActive(other)
                || (other.isUnstarted() && QUEUE_ORDER.compare(other, target) < 0));
    }

    private List<Punishment> promotable(List<Punishment> punishments) {
        List<Punishment> promotable = new ArrayList<>();
        for (ResolvedCategory category : QUEUED_CATEGORIES) {
            List<Punishment> inCategory = punishments.stream()
                .filter(punishment -> statusCalculator.getEffectiveCategory(punishment) == category)
                .toList();
            if (inCategory.stream().anyMatch(statusCalculator::isPunishmentActive)) {
                continue;
            }
            inCategory.stream()
                .filter(Punishment::isUnstarted)
                .min(QUEUE_ORDER)
                .ifPresent(promotable::add);
        }
        return promotable;
    }
}
