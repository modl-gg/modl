package gg.modl.backend.status;

public record StatusFact(StatusItem item, String value, StatusLevel level) {
    private static final int MAX_VALUE_LENGTH = 200;

    public StatusFact {
        value = printable(value);
    }

    private static String printable(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String singleLine = value.replaceAll("\\p{Cntrl}", " ").trim();
        return singleLine.length() <= MAX_VALUE_LENGTH ? singleLine : singleLine.substring(0, MAX_VALUE_LENGTH);
    }
}
