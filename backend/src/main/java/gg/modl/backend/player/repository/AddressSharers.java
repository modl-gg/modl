package gg.modl.backend.player.repository;

import gg.modl.backend.player.data.Player;
import java.util.List;

public record AddressSharers(List<Player> players, boolean truncated) {
    public AddressSharers {
        players = List.copyOf(players);
    }
}
