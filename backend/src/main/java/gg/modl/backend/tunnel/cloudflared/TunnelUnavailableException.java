package gg.modl.backend.tunnel.cloudflared;

public class TunnelUnavailableException extends Exception {
    private final String reason;

    public TunnelUnavailableException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public TunnelUnavailableException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
