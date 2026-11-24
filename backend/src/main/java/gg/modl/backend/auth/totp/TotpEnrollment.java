package gg.modl.backend.auth.totp;

public record TotpEnrollment(String secret, String keyUri, long expiresInSeconds) {
    @Override
    public String toString() {
        return "TotpEnrollment[redacted]";
    }
}
