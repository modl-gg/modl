package gg.modl.backend.email;

public enum MailDeliveryOutcome {
    DELIVERED,
    CONNECT_FAILED,
    TLS_FAILED,
    AUTH_FAILED,
    REJECTED,
    TIMED_OUT
}
