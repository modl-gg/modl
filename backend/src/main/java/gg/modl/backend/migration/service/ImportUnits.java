package gg.modl.backend.migration.service;

import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.punishment.Punishment;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

public final class ImportUnits {
    public static final int ROWS_PER_UNIT = 500;

    private ImportUnits() {
    }

    public static <T> List<List<T>> byRowCount(List<T> items, ToIntFunction<T> rowsOf) {
        List<List<T>> units = new ArrayList<>();
        List<T> unit = new ArrayList<>();
        int unitRows = 0;
        for (T item : items) {
            int rows = rowsOf.applyAsInt(item);
            if (!unit.isEmpty() && unitRows + rows > ROWS_PER_UNIT) {
                units.add(unit);
                unit = new ArrayList<>();
                unitRows = 0;
            }
            unit.add(item);
            unitRows += rows;
        }
        if (!unit.isEmpty()) {
            units.add(unit);
        }
        return units;
    }

    public static int rowCount(Player player) {
        int rows = 1 + player.getUsernames().size() + player.getIpAddresses().size() + player.getNotes().size();
        for (Punishment punishment : player.getPunishments()) {
            rows += 1 + punishment.getModifications().size() + punishment.getNotes().size()
                + punishment.getEvidence().size() + punishment.getAttachedTicketIds().size();
        }
        return rows;
    }
}
