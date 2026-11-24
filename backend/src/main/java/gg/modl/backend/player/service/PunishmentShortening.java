package gg.modl.backend.player.service;

import gg.modl.backend.infrastructure.exception.ForbiddenException;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentState;
import gg.modl.backend.role.service.PermissionService;
import org.jspecify.annotations.Nullable;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class PunishmentShortening {
    public static final String NEEDS_PARDON = "PUNISHMENT_SHORTENING_NEEDS_PARDON";
    public static final List<String> REQUIRED_PERMISSIONS =
        List.of(PermissionService.PUNISHMENT_MODIFY_DURATION, PermissionService.PUNISHMENT_MODIFY_PARDON);

    private static final String NOT_PERMITTED =
        "Making a punishment end earlier needs the permissions to change its duration and to pardon it";

    private final Supplier<? extends Predicate<String>> permissions;
    private final AtomicReference<Boolean> permitted = new AtomicReference<>();

    private PunishmentShortening(Supplier<? extends Predicate<String>> permissions) {
        this.permissions = permissions;
    }

    public static PunishmentShortening grantedBy(Supplier<? extends Predicate<String>> permissions) {
        return new PunishmentShortening(permissions);
    }

    public void requireFor(Punishment stored, @Nullable Long newDurationMillis, Instant at) {
        if (PunishmentState.endsEarlierWith(stored, newDurationMillis, at)) {
            require();
        }
    }

    public void requireForAll(Collection<Punishment> stored, @Nullable Long newDurationMillis, Instant at) {
        if (stored.stream().anyMatch(punishment -> PunishmentState.endsEarlierWith(punishment, newDurationMillis, at))) {
            require();
        }
    }

    public void require() {
        if (!permitted()) {
            throw new ForbiddenException(NOT_PERMITTED, NEEDS_PARDON);
        }
    }

    private boolean permitted() {
        Boolean decided = permitted.get();
        if (decided == null) {
            decided = REQUIRED_PERMISSIONS.stream().allMatch(permissions.get());
            permitted.set(decided);
        }
        return decided;
    }
}
