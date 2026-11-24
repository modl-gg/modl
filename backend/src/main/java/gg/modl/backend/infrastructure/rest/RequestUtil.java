package gg.modl.backend.infrastructure.rest;

import gg.modl.backend.auth.session.AuthSessionData;
import gg.modl.backend.infrastructure.exception.ValidationException;
import gg.modl.backend.server.data.Server;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RequestUtil {

    private static final Pattern ACTING_STAFF_ID = Pattern.compile("[0-9A-Za-z_-]{1,64}");

    private RequestUtil() {
    }

    public static Server getRequestServer(HttpServletRequest request) {
        return Objects.requireNonNull((Server) request.getAttribute(RequestAttribute.SERVER), "Server should not be null if being called from panel route!");
    }

    @Nullable
    public static String getSessionEmail(HttpServletRequest request) {
        AuthSessionData session = getSession(request);
        return session != null ? session.getEmail() : null;
    }

    @Nullable
    public static AuthSessionData getSession(HttpServletRequest request) {
        return (AuthSessionData) request.getAttribute(RequestAttribute.SESSION);
    }

    public static AuthSessionData requireSession(HttpServletRequest request) {
        return Objects.requireNonNull(getSession(request), "An authenticated panel session is required for this route");
    }

    public static String requireSessionEmail(HttpServletRequest request) {
        return requireSession(request).getEmail();
    }

    @Nullable
    public static String getActingStaffId(HttpServletRequest request) {
        String actingStaffId = request.getHeader("X-Acting-Staff-Id");
        if (actingStaffId == null) {
            return null;
        }
        String trimmed = actingStaffId.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (!ACTING_STAFF_ID.matcher(trimmed).matches()) {
            throw new ValidationException("X-Acting-Staff-Id is not a valid staff id", "INVALID_ARGUMENT");
        }
        return trimmed;
    }
}
