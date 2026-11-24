package gg.modl.backend.auth.session;

public enum AuthMethod {
    EMAIL_CODE,
    PASSWORD,
    PASSKEY,
    LOGIN_LINK,
    SETUP_CODE,
    INVITATION;

    public static AuthMethod fromStored(String stored) {
        return valueOf(stored);
    }
}
