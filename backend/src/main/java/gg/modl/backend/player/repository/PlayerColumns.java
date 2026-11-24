package gg.modl.backend.player.repository;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;

import gg.modl.backend.content.CappedText;
import gg.modl.backend.database.StorableText;
import gg.modl.backend.database.TextLimits;
import gg.modl.backend.infrastructure.util.IpAddresses;
import gg.modl.backend.infrastructure.util.UuidUtils;
import gg.modl.backend.player.data.UsernameEntry;
import org.jooq.TableField;
import org.jspecify.annotations.Nullable;

public final class PlayerColumns {
    static final String UNKNOWN_USERNAME = "Unknown";


    private PlayerColumns() {
    }

    public static String storedUsername(String username) {
        return fit(PLAYERS.USERNAME, username);
    }

    @Nullable
    public static String storedSkinHash(@Nullable String skinHash) {
        return fitOrNull(PLAYERS.LAST_SKIN_HASH, skinHash);
    }

    static String fit(TableField<?, String> field, String value) {
        return TextLimits.require(field, CappedText.of(StorableText.strip(value), TextLimits.limit(field)));
    }

    @Nullable
    static String fitIfPresent(TableField<?, String> field, @Nullable String value) {
        return value == null ? null : fit(field, value);
    }

    @Nullable
    static String fitOrNull(TableField<?, String> field, @Nullable String value) {
        return value == null || value.isBlank() ? null : fit(field, value);
    }

    static String uuid(String minecraftUuid) {
        return UuidUtils.normalize(minecraftUuid);
    }

    static String usernameKey(String username) {
        return fit(PLAYER_USERNAMES.USERNAME_LOWER, UsernameEntry.lookupKey(username));
    }

    @Nullable
    static String address(@Nullable String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String address = ip.trim();
        int limit = TextLimits.limit(PLAYER_IPS.IP);
        return address.length() > limit ? IpAddresses.withoutZone(address) : address;
    }

    @Nullable
    static String storableAddress(@Nullable String ip) {
        String address = address(ip);
        if (address == null || !StorableText.isStorable(address) || address.length() > TextLimits.limit(PLAYER_IPS.IP)) {
            return null;
        }
        return TextLimits.require(PLAYER_IPS.IP, address);
    }
}
