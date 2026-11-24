package gg.modl.backend.iplookup.probe;

public enum IpLookupProbeOutcome {
    WORKING,
    NO_VERDICT,
    REFUSED,
    DAILY_LIMIT_REACHED,
    UNEXPECTED_ANSWER,
    UNREACHABLE,
    BUSY
}
