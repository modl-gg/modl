package gg.modl.backend.migration.data;

import java.util.Arrays;
import java.util.Optional;

public enum ImportSource {
    LITEBANS("litebans", "LiteBans"),
    ADVANCEDBAN("advancedban", "AdvancedBan"),
    LIBERTYBANS("libertybans", "LibertyBans"),
    BANMANAGER("banmanager", "BanManager"),
    BANSYSTEM("bansystem", "BanSystem"),
    VANILLA("vanilla", "Vanilla and EssentialsX");

    private final String id;
    private final String displayName;

    ImportSource(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<ImportSource> fromId(String id) {
        return Arrays.stream(values()).filter(source -> source.id.equals(id)).findFirst();
    }

    public static String displayNameOf(String id) {
        return fromId(id).map(ImportSource::displayName).orElse(id);
    }
}
