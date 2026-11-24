package gg.modl.minecraft.core.staff.action;

import gg.modl.minecraft.api.http.request.ActionIssuer;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

public final class AuthorizedStaff {
    private static final AuthorizedStaff CONSOLE = new AuthorizedStaff(null, null);

    private final @Nullable UUID uuid;
    private final @Nullable String staffId;

    private AuthorizedStaff(@Nullable UUID uuid, @Nullable String staffId) {
        this.uuid = uuid;
        this.staffId = staffId;
    }

    static AuthorizedStaff console() {
        return CONSOLE;
    }

    static AuthorizedStaff player(UUID uuid, @Nullable String staffId) {
        return new AuthorizedStaff(uuid, staffId);
    }

    public boolean isConsole() {
        return uuid == null;
    }

    public @Nullable String issuerId() {
        return isConsole() ? null : authorizedStaffId();
    }

    public ActionIssuer actionIssuer() {
        return isConsole() ? ActionIssuer.console() : ActionIssuer.staff(authorizedStaffId());
    }

    private String authorizedStaffId() {
        String authorized = staffId;
        if (authorized == null) {
            throw new IllegalStateException("player " + uuid + " was not authorized as a staff member");
        }
        return authorized;
    }
}
