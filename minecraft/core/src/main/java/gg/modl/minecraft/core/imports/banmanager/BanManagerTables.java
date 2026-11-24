package gg.modl.minecraft.core.imports.banmanager;

import gg.modl.minecraft.core.imports.support.ConfigFile;
import lombok.Value;

import java.util.Arrays;
import java.util.List;

@Value
class BanManagerTables {
    private static final String TABLES = "databases.local.tables.";

    String players, history, bans, banRecords, mutes, muteRecords, warnings, kicks, notes;
    String ipBans, ipBanRecords, ipMutes, ipMuteRecords, ipRangeBans, ipRangeBanRecords, nameBans, nameBanRecords;

    static BanManagerTables of(ConfigFile config) {
        return new BanManagerTables(
                name(config, "players", "bm_players"),
                name(config, "playerHistory", "bm_player_history"),
                name(config, "playerBans", "bm_player_bans"),
                name(config, "playerBanRecords", "bm_player_ban_records"),
                name(config, "playerMutes", "bm_player_mutes"),
                name(config, "playerMuteRecords", "bm_player_mute_records"),
                name(config, "playerWarnings", "bm_player_warnings"),
                name(config, "playerKicks", "bm_player_kicks"),
                name(config, "playerNotes", "bm_player_notes"),
                name(config, "ipBans", "bm_ip_bans"),
                name(config, "ipBanRecords", "bm_ip_ban_records"),
                name(config, "ipMutes", "bm_ip_mutes"),
                name(config, "ipMuteRecords", "bm_ip_mute_records"),
                name(config, "ipRangeBans", "bm_ip_range_bans"),
                name(config, "ipRangeBanRecords", "bm_ip_range_ban_records"),
                name(config, "nameBans", "bm_name_bans"),
                name(config, "nameBanRecords", "bm_name_ban_records"));
    }

    List<String> all() {
        return Arrays.asList(players, history, bans, banRecords, mutes, muteRecords, warnings, kicks, notes, ipBans,
                ipBanRecords, ipMutes, ipMuteRecords, ipRangeBans, ipRangeBanRecords, nameBans, nameBanRecords);
    }

    private static String name(ConfigFile config, String key, String fallback) {
        String configured = config.string(TABLES + key, fallback).trim();
        return configured.matches("[A-Za-z0-9_]{1,64}") ? configured : fallback;
    }
}
