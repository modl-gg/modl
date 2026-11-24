package gg.modl.backend.player.dto.response;

import org.jspecify.annotations.Nullable;

public interface PunishmentPreviewView {
    int getStatus();

    boolean isSuccess();

    @Nullable String getMessage();

    @Nullable String getSocialStatus();

    @Nullable String getGameplayStatus();

    int getSocialPoints();

    int getGameplayPoints();

    @Nullable String getOffenderStatus();

    @Nullable PunishmentSeverityPreviewView getLenient();

    @Nullable PunishmentSeverityPreviewView getRegular();

    @Nullable PunishmentSeverityPreviewView getAggravated();

    @Nullable PunishmentSeverityPreviewView getSingleSeverity();

    boolean isSingleSeverityPunishment();

    boolean isPermanentUntilUsernameChange();

    boolean isPermanentUntilSkinChange();

    boolean isCanBeAltBlocking();

    boolean isCanBeStatWiping();

    @Nullable String getCategory();
}
