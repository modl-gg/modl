package gg.modl.backend.auth.password;

public record PreparedPassword(String hash) {
    @Override
    public String toString() {
        return "PreparedPassword[redacted]";
    }
}
