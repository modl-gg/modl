package gg.modl.minecraft.core.staff.action;

public final class StaffActionDenied extends Exception {
    private static final long serialVersionUID = 1L;

    private final String reason;

    public StaffActionDenied(String reason) {
        super(reason, null, false, false);
        this.reason = reason;
    }

    @Override
    public String getMessage() {
        return reason;
    }
}
