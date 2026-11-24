package gg.modl.minecraft.features.staffmode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static gg.modl.minecraft.core.util.Java8Collections.entry;

final class StartingLocationFormat {
    private static final String HEADER = "modl-staff-mode-returns 1";
    private static final String SEPARATOR = " ";
    private static final int PLAYER = 0;
    private static final int WORLD = 1;
    private static final int X = 2;
    private static final int Y = 3;
    private static final int Z = 4;
    private static final int YAW = 5;
    private static final int PITCH = 6;
    private static final int RECORDED_AT = 7;
    private static final int FIELD_COUNT = 8;

    private StartingLocationFormat() {
    }

    static String write(Map<UUID, PendingReturn> pendingReturns) {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        pendingReturns.forEach((player, pending) -> {
            StartingLocation location = pending.getLocation();
            text.append(player).append(SEPARATOR)
                    .append(location.getWorldId()).append(SEPARATOR)
                    .append(location.getX()).append(SEPARATOR)
                    .append(location.getY()).append(SEPARATOR)
                    .append(location.getZ()).append(SEPARATOR)
                    .append(location.getYaw()).append(SEPARATOR)
                    .append(location.getPitch()).append(SEPARATOR)
                    .append(pending.getRecordedAt()).append('\n');
        });
        return text.toString();
    }

    static Map<UUID, PendingReturn> read(List<String> lines) throws IOException {
        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
            throw new IOException("it does not start with \"" + HEADER + "\"");
        }
        Map<UUID, PendingReturn> pendingReturns = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            readLine(line).ifPresent(entry -> pendingReturns.put(entry.getKey(), entry.getValue()));
        }
        return pendingReturns;
    }

    private static Optional<Map.Entry<UUID, PendingReturn>> readLine(String line) {
        String[] fields = line.split(SEPARATOR);
        if (fields.length != FIELD_COUNT) return Optional.empty();
        try {
            double x = Double.parseDouble(fields[X]);
            double y = Double.parseDouble(fields[Y]);
            double z = Double.parseDouble(fields[Z]);
            float yaw = Float.parseFloat(fields[YAW]);
            float pitch = Float.parseFloat(fields[PITCH]);
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
                return Optional.empty();
            }
            StartingLocation location = new StartingLocation(UUID.fromString(fields[WORLD]), x, y, z, yaw, pitch);
            PendingReturn pending = new PendingReturn(location, Long.parseLong(fields[RECORDED_AT]));
            return Optional.of(entry(UUID.fromString(fields[PLAYER]), pending));
        } catch (IllegalArgumentException unreadable) {
            return Optional.empty();
        }
    }
}
