package gg.modl.backend.hostedimport.archive;

import java.util.HexFormat;
import java.util.Locale;

public record ObjectIdValue(String hex) {
    public ObjectIdValue {
        hex = hex.toLowerCase(Locale.ROOT);
    }

    static ObjectIdValue of(byte[] bytes) {
        return new ObjectIdValue(HexFormat.of().formatHex(bytes));
    }

    @Override
    public String toString() {
        return hex;
    }
}
