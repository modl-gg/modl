package gg.modl.backend.support;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import java.util.ArrayList;
import java.util.List;

public final class TestDataProvider {
    private static final String DEFAULT_UUID = "069a79f4-44e9-4726-a5be-fca90e38aaf5";
    private static final String DEFAULT_USERNAME = "Notch";
    private static final int DEFAULT_TYPE_ORDINAL = 14;
    private static final String DEFAULT_CATEGORY = "BAN";
    private static final int MAX_PLAYERS = 20;
    private static final String PUNISHMENT_TYPES_SETTINGS = "punishmentTypes";

    private static boolean initialized;
    private static List<PlayerInfo> players;
    private static PlayerInfo playerWithPunishments;
    private static List<PunishmentTypeInfo> punishmentTypes;

    private TestDataProvider() {}

    public static List<PlayerInfo> getPlayers() {
        initialize();
        return players;
    }

    public static PlayerInfo getPlayerWithPunishments() {
        initialize();
        return playerWithPunishments;
    }

    public static List<PunishmentTypeInfo> getPunishmentTypes() {
        initialize();
        return punishmentTypes;
    }

    private static synchronized void initialize() {
        if (initialized) {
            return;
        }
        loadDefaults();
        if (TestDatabase.isAvailable()) {
            loadFromDatabase(TestDatabase.getInstance());
        }
        initialized = true;
    }

    private static void loadDefaults() {
        PlayerInfo player = new PlayerInfo(DEFAULT_UUID, DEFAULT_USERNAME);
        players = List.of(player);
        playerWithPunishments = player;
        punishmentTypes = List.of(new PunishmentTypeInfo(DEFAULT_TYPE_ORDINAL, "Default", DEFAULT_CATEGORY));
    }

    private static void loadFromDatabase(TestDatabase database) {
        List<PlayerInfo> storedPlayers = database.players(MAX_PLAYERS).stream().map(TestDataProvider::toPlayerInfo).toList();
        if (!storedPlayers.isEmpty()) {
            players = storedPlayers;
        }
        playerWithPunishments = database.anyPunishedPlayer().map(TestDataProvider::toPlayerInfo).orElse(players.getFirst());
        List<PunishmentTypeInfo> storedTypes = database.settingsJson(PUNISHMENT_TYPES_SETTINGS)
            .map(TestDataProvider::toPunishmentTypes)
            .orElse(List.of());
        if (!storedTypes.isEmpty()) {
            punishmentTypes = storedTypes;
        }
    }

    private static PlayerInfo toPlayerInfo(PlayersRecord player) {
        return new PlayerInfo(player.getMinecraftUuid(), player.getUsername());
    }

    private static List<PunishmentTypeInfo> toPunishmentTypes(String json) {
        List<PunishmentTypeInfo> types = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            JsonObject type = element.getAsJsonObject();
            String category = type.has("category") ? type.get("category").getAsString() : DEFAULT_CATEGORY;
            types.add(new PunishmentTypeInfo(type.get("ordinal").getAsInt(), type.get("name").getAsString(), category));
        }
        return List.copyOf(types);
    }

    public record PlayerInfo(String uuid, String username) {}

    public record PunishmentTypeInfo(int ordinal, String name, String category) {}
}
