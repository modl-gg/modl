package io.papermc.paper.connection;

import com.destroystokyo.paper.profile.PlayerProfile;

public interface PlayerLoginConnection extends PlayerConnection {
    PlayerProfile getAuthenticatedProfile();
}
