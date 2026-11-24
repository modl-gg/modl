package gg.modl.backend.player.service;

import gg.modl.backend.player.data.UsernameEntry;
import java.util.List;
import lombok.experimental.UtilityClass;

@UtilityClass
public class PlayerDataUtils {
    private final String UNKNOWN_USERNAME = "Unknown";

    public String extractLatestUsername(List<UsernameEntry> usernames) {
        if (usernames == null || usernames.isEmpty()) {
            return UNKNOWN_USERNAME;
        }
        return usernames.getLast().username();
    }
}
