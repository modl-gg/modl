package gg.modl.minecraft.core.util;

import gg.modl.minecraft.api.http.ApiClientException;
import gg.modl.minecraft.api.http.PanelUnavailableException;
import gg.modl.minecraft.core.support.MapLocaleManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiFailuresTest {
    private static final String BACKEND_TEXT = "You do not have permission to dismiss this report";

    private final MapLocaleManager locale = new MapLocaleManager()
            .put("general.no_permission", "No permission")
            .put("api_errors.panel_unavailable", "Panel restarting")
            .put("menus.common.dismiss_report_failed", "Could not dismiss: {error}")
            .put(ApiFailures.SHORTENING_NEEDS_PARDON_KEY, "Shortening needs pardon")
            .put(ApiFailures.DATABASE_MOVING_KEY, "Moving");

    @Test
    void aRefusedRequestShowsTheLocalizedPermissionMessageInsteadOfTheBackendText() {
        assertEquals("No permission", ApiFailures.describe(locale, "menus.common.dismiss_report_failed",
                new CompletionException(new ApiClientException(403, BACKEND_TEXT))));
        assertEquals("No permission", ApiFailures.describe(locale, "menus.common.dismiss_report_failed",
                new ApiClientException(403, BACKEND_TEXT)));
    }

    @Test
    void anUnavailablePanelShowsTheRestartingMessage() {
        assertEquals("Panel restarting", ApiFailures.describe(locale, "menus.common.dismiss_report_failed",
                new CompletionException(new PanelUnavailableException("/v1/reports", 503, "down"))));
    }

    @Test
    void aPausedDatabaseSaysThatModlIsMoving() {
        assertEquals("Moving", ApiFailures.describe(locale, "menus.common.dismiss_report_failed",
                new CompletionException(new PanelUnavailableException("/v1/reports", 503, "down", ApiFailures.DATABASE_MOVING))));
    }

    @Test
    void otherFailuresUseTheGivenKeyWithTheSanitizedMessage() {
        assertEquals("Could not dismiss: Report is already closed", ApiFailures.describe(locale,
                "menus.common.dismiss_report_failed",
                new CompletionException(new ApiClientException(409, "Report is already closed"))));
    }

    @Test
    void aShorteningRefusalExplainsWhyInsteadOfTheGenericPermissionMessage() {
        assertEquals("Shortening needs pardon", ApiFailures.describe(locale, "menus.common.dismiss_report_failed",
                new CompletionException(new ApiClientException(403, BACKEND_TEXT, ApiFailures.SHORTENING_NEEDS_PARDON))));
    }
}
