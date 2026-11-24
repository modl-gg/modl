package gg.modl.minecraft.api;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

@Getter @NoArgsConstructor @AllArgsConstructor
public class SimplePunishment {
    private static final String CATEGORY_BAN = "BAN", CATEGORY_MUTE = "MUTE", CATEGORY_KICK = "KICK";

    private String type;
    private @Nullable String category;
    private @Setter @Nullable Long expiration;
    private String description;
    private String id;
    private @Nullable String issuerName;
    private @Nullable Long issuedAt;
    private @Nullable String playerDescription;
    private boolean started;
    private int ordinal;
    private boolean silent;

    public boolean isBan() {
        if (category != null) return CATEGORY_BAN.equalsIgnoreCase(category);
        PunishmentTypeClassifier classifier = PunishmentTypeClassifiers.active();
        if (classifier.isPopulated()) return classifier.isBan(ordinal);
        if (ordinal >= PunishmentTypeClassifier.ORDINAL_BAN && ordinal <= PunishmentTypeClassifier.ORDINAL_BLACKLIST) return true;
        return CATEGORY_BAN.equalsIgnoreCase(type);
    }

    public boolean isMute() {
        if (category != null) return CATEGORY_MUTE.equalsIgnoreCase(category);
        PunishmentTypeClassifier classifier = PunishmentTypeClassifiers.active();
        if (classifier.isPopulated()) return classifier.isMute(ordinal);
        if (ordinal == PunishmentTypeClassifier.ORDINAL_MUTE) return true;
        return CATEGORY_MUTE.equalsIgnoreCase(type);
    }

    public boolean isKick() {
        return PunishmentTypeClassifiers.active().isKick(ordinal) || CATEGORY_KICK.equalsIgnoreCase(type);
    }

    public @Nullable String getPlayerFacingReason() {
        return PlayerFacingReason.of(ordinal, description, playerDescription);
    }

    public boolean isPermanent() {
        return expiration == null;
    }

    public boolean isExpired() {
        return expiration != null && expiration < System.currentTimeMillis();
    }

    public @Nullable Instant getIssuedInstant() {
        return issuedAt != null ? Instant.ofEpochMilli(issuedAt) : null;
    }
}
