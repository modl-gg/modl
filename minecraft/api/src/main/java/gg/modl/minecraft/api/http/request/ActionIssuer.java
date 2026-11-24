package gg.modl.minecraft.api.http.request;

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

@EqualsAndHashCode
@ToString
public final class ActionIssuer {
    private static final ActionIssuer CONSOLE = new ActionIssuer(null);

    private final @Nullable String staffId;

    private ActionIssuer(@Nullable String staffId) {
        this.staffId = staffId;
    }

    public static ActionIssuer console() {
        return CONSOLE;
    }

    public static ActionIssuer staff(String staffId) {
        return new ActionIssuer(Objects.requireNonNull(staffId, "staffId"));
    }

    public boolean isConsole() {
        return staffId == null;
    }

    public @Nullable String getStaffId() {
        return staffId;
    }

    public void writeTo(Consumer<String> staffIdField, Consumer<Boolean> consoleField) {
        if (staffId == null) consoleField.accept(Boolean.TRUE);
        else staffIdField.accept(staffId);
    }
}
