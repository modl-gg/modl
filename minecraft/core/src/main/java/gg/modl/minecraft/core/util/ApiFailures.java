package gg.modl.minecraft.core.util;

import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.core.locale.LocaleManager;

import java.util.Map;

import static gg.modl.minecraft.core.util.Java8Collections.mapOf;

public final class ApiFailures {
    public static final String SHORTENING_NEEDS_PARDON = "PUNISHMENT_SHORTENING_NEEDS_PARDON";
    public static final String SHORTENING_NEEDS_PARDON_KEY = "api_errors.shortening_needs_pardon";
    public static final String DATABASE_MOVING = "DATABASE_MOVING";
    public static final String DATABASE_MOVING_KEY = "api_errors.database_moving";
    public static final String PANEL_UNAVAILABLE_KEY = "api_errors.panel_unavailable";

    private static final Map<String, String> REFUSAL_KEYS = mapOf(SHORTENING_NEEDS_PARDON, SHORTENING_NEEDS_PARDON_KEY);

    private ApiFailures() {
    }

    public static String describe(LocaleManager localeManager, String errorKey, Throwable failure) {
        Throwable cause = Throwables.rootCause(failure);
        if (cause instanceof PanelUnavailableException) return unavailable(localeManager, (PanelUnavailableException) cause);
        String refusalKey = REFUSAL_KEYS.get(ApiClientException.codeOf(cause));
        if (refusalKey != null) return localeManager.getMessage(refusalKey);
        if (ApiClientException.isPermissionDenied(cause)) return localeManager.getMessage("general.no_permission");
        return localeManager.getMessage(errorKey,
                mapOf("error", localeManager.sanitizeErrorMessage(cause.getMessage())));
    }

    public static String unavailable(LocaleManager localeManager, PanelUnavailableException failure) {
        return localeManager.getMessage(isDatabaseMoving(failure) ? DATABASE_MOVING_KEY : PANEL_UNAVAILABLE_KEY);
    }

    public static boolean isDatabaseMoving(Throwable failure) {
        return failure instanceof PanelUnavailableException
                && DATABASE_MOVING.equals(((PanelUnavailableException) failure).getCode());
    }
}
