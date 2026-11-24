package gg.modl.backend.hostedimport.archive;

public class UnreadableDocumentException extends RuntimeException {
    private final boolean tooLarge;

    public UnreadableDocumentException(String reason, boolean tooLarge) {
        super(reason);
        this.tooLarge = tooLarge;
    }

    public boolean tooLarge() {
        return tooLarge;
    }
}
