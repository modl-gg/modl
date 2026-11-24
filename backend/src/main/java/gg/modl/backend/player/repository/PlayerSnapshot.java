package gg.modl.backend.player.repository;

import gg.modl.backend.player.data.Player;
import java.time.Instant;

public record PlayerSnapshot(Player player, Instant createdAt) {
}
