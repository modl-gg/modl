package gg.modl.backend.auth.session;

public record IssuedSession(AuthSessionData session, String sessionKey) {

    @Override
    public String toString() {
        return "IssuedSession[" + session + "]";
    }
}
