package gg.modl.backend.player.repository;

import java.util.List;
import org.jspecify.annotations.Nullable;

public record PunishmentListPage(List<PunishmentRecord> items, @Nullable PunishmentListCursor next) {
}
