package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.PlayerFacingReason;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentModification;
import gg.modl.backend.player.data.punishment.PunishmentModificationType;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.player.dto.response.PlayerStandingView;
import gg.modl.backend.player.dto.response.PlayerStandingView.Entry;
import gg.modl.backend.player.dto.response.PlayerStandingView.State;
import gg.modl.backend.player.repository.PlayerRepository;
import gg.modl.backend.player.repository.PunishmentRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.settings.data.PunishmentType;
import gg.modl.backend.settings.service.PunishmentTypeService;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PlayerStandingService {
    private static final int PUNISHMENT_LIMIT = 14;
    private static final String OTHER_CATEGORY = "OTHER";
    private static final Comparator<Punishment> NEWEST_FIRST =
        Comparator.comparing(Punishment::getIssued).thenComparing(Punishment::getId).reversed();

    private final PlayerRepository playerRepository;
    private final PunishmentRepository punishmentRepository;
    private final PunishmentTypeService punishmentTypeService;
    private final PlayerStatusCalculator statusCalculator;

    public PlayerStandingView standingOf(Server server, String playerUuid) {
        Player player = playerRepository.findByMinecraftUuid(UuidUtils.normalize(playerUuid))
            .orElseThrow(() -> new ResourceNotFoundException("Player not found"));
        List<Punishment> punishments = punishmentRepository.findByPlayer(player.getId());
        PlayerStatusCalculator.PlayerStatus status = statusCalculator.calculateStatus(server, punishments);
        PunishmentTypeLookup types = PunishmentTypeLookup.of(server, punishmentTypeService);

        List<Entry> entries = punishments.stream()
            .filter(punishment -> !isKick(punishment, types))
            .sorted(NEWEST_FIRST)
            .limit(PUNISHMENT_LIMIT)
            .map(punishment -> entryOf(punishment, types))
            .toList();
        return new PlayerStandingView(status.social(), status.gameplay(), status.socialPoints(), status.gameplayPoints(), entries);
    }

    private Entry entryOf(Punishment punishment, PunishmentTypeLookup types) {
        int ordinal = punishment.getTypeOrdinal();
        ResolvedCategory category = statusCalculator.getEffectiveCategory(punishment);
        Instant pardonedAt = latestDate(punishment, PunishmentModificationType::isPardon);
        Instant endedAt = pardonedAt != null ? pardonedAt : latestDate(punishment, PunishmentModificationType::endsEnforcement);

        return new Entry(
            punishment.getId(),
            types.name(ordinal),
            category.isEnforced() ? category.name() : OTHER_CATEGORY,
            punishment.getIssued().toEpochMilli(),
            durationOf(punishment),
            stateOf(punishment, pardonedAt, endedAt),
            epochMillis(endedAt),
            epochMillis(statusCalculator.getEffectiveExpiry(punishment)),
            reasonOf(punishment, types.live(ordinal)));
    }

    private State stateOf(Punishment punishment, @Nullable Instant pardonedAt, @Nullable Instant endedAt) {
        if (pardonedAt != null) {
            return State.PARDONED;
        }
        if (endedAt != null) {
            return State.ROLLED_BACK;
        }
        if (punishment.getStarted() == null) {
            return State.QUEUED;
        }
        return statusCalculator.isPunishmentActive(punishment) ? State.ACTIVE : State.INACTIVE;
    }

    @Nullable
    private static String reasonOf(Punishment punishment, @Nullable PunishmentType type) {
        String reason = PlayerFacingReason.of(punishment.getTypeOrdinal(), punishment.data().reason(),
            type != null ? type.getPlayerDescription() : null);
        return PlayerFacingReason.DEFAULT.equals(reason) ? null : reason;
    }

    @Nullable
    private static Long durationOf(Punishment punishment) {
        PunishmentModification change = latest(punishment, PunishmentModificationType::isDurationChange);
        Long duration = change != null ? change.effectiveDuration() : punishment.data().duration();
        return duration != null && duration > 0 ? duration : null;
    }

    @Nullable
    private static Instant latestDate(Punishment punishment, Predicate<String> matches) {
        PunishmentModification modification = latest(punishment, matches);
        return modification != null ? modification.date() : null;
    }

    @Nullable
    private static PunishmentModification latest(Punishment punishment, Predicate<String> matches) {
        return punishment.getModifications().reversed().stream()
            .filter(modification -> matches.test(modification.type()))
            .findFirst()
            .orElse(null);
    }

    private static boolean isKick(Punishment punishment, PunishmentTypeLookup types) {
        PunishmentType type = types.live(punishment.getTypeOrdinal());
        return punishment.getTypeOrdinal() == Punishment.KICK_TYPE_ORDINAL || (type != null && type.isKick());
    }

    @Nullable
    private static Long epochMillis(@Nullable Instant instant) {
        return instant != null ? instant.toEpochMilli() : null;
    }
}
