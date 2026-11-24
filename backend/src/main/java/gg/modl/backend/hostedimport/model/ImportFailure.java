package gg.modl.backend.hostedimport.model;

public class ImportFailure extends RuntimeException {
    private final ImportReason reason;

    public ImportFailure(ImportReason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public ImportFailure(ImportReason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public ImportReason reason() {
        return reason;
    }
}
