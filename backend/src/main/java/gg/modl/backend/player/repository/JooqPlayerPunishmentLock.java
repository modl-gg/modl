package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;

import gg.modl.backend.database.ChildRows;
import gg.modl.backend.database.TextLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JooqPlayerPunishmentLock implements PlayerPunishmentLock {
    private final ChildRows childRows;

    @Override
    public void lock(String playerId) {
        childRows.lockRow(PLAYERS, PLAYERS.ID, TextLimits.require(PLAYERS.ID, playerId));
    }
}
