package gg.modl.backend.player.data;

import java.util.Set;

public final class PlayerDataKeys {
    public static final String IS_ONLINE = "isOnline";
    public static final String LAST_LOGIN = "lastLogin";
    public static final String LAST_LOGOUT = "lastLogout";
    public static final String LAST_SERVER = "lastServer";
    public static final String TOTAL_PLAYTIME_SECONDS = "totalPlaytimeSeconds";
    public static final String FIRST_JOIN = "firstJoin";
    public static final String LAST_SKIN_HASH = "lastSkinHash";
    public static final String PENDING_NOTIFICATIONS = "pendingNotifications";
    public static final String LINKED_ACCOUNTS = "linkedAccounts";
    public static final String LAST_LINKED_UPDATE = "lastLinkedUpdate";
    public static final String COUNTRY = "country";
    public static final String REGION = "region";
    public static final String ASN = "asn";
    public static final String PROXY = "proxy";
    public static final String HOSTING = "hosting";
    public static final Set<String> STRUCTURED_KEYS = Set.of(
        IS_ONLINE, LAST_LOGIN, LAST_LOGOUT, LAST_SERVER, TOTAL_PLAYTIME_SECONDS, FIRST_JOIN, LAST_SKIN_HASH,
        LAST_LINKED_UPDATE, PENDING_NOTIFICATIONS, LINKED_ACCOUNTS
    );

    private PlayerDataKeys() {
    }
}
