package gg.modl.backend.player.repository;

import gg.modl.backend.player.data.punishment.PunishmentIssuerKind;
import gg.modl.backend.database.EntityIds;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.player.data.punishment.PunishmentDataView;
import gg.modl.backend.player.data.punishment.ResolvedCategory;
import gg.modl.backend.support.sql.SqlTestDatabase;
import org.jspecify.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerFixtures {
    public static final Instant NOW = SqlTestDatabase.START;

    private PlayerFixtures() {
    }

    public static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    public static String login(PlayerRepository players, String name, @Nullable String ip, Instant at) {
        return login(players, name, ip, null, at);
    }

    public static String login(PlayerRepository players, String name, @Nullable String ip, @Nullable IpGeo geo, Instant at) {
        return players.recordLogin(new LoginUpdate(uuid(name).toString(), name, ip, geo, null, null, at)).playerId();
    }

    public static IpGeo proxy() {
        return new IpGeo("DE", null, null, true, false);
    }

    public static Punishment punishment(int ordinal, ResolvedCategory category, Instant issued, @Nullable Instant started,
                                        @Nullable Long duration) {
        Map<String, Object> data = new HashMap<>();
        PunishmentDataView view = PunishmentDataView.ofMap(data);
        view.setEnforcementCategory(category);
        if (duration != null) {
            view.setDuration(duration);
        }
        return new Punishment(EntityIds.next(), ordinal, "Moderator", null, PunishmentIssuerKind.STAFF, issued, started,
            new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), data);
    }

    public static Punishment insert(PunishmentRepository punishments, String playerId, Punishment punishment) {
        if (!punishments.insertIfAbsent(playerId, punishment, punishment.enforcementCategory())) {
            throw new IllegalStateException("The fixture punishment id was taken");
        }
        return punishment;
    }
}
