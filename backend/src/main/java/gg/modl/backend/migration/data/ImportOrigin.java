package gg.modl.backend.migration.data;

import gg.modl.backend.content.CappedText;
import gg.modl.backend.infrastructure.exception.ValidationException;
import org.jspecify.annotations.Nullable;
import java.util.regex.Pattern;

public record ImportOrigin(String serverName, @Nullable String address) {
    public static final int MAX_SERVER_NAME_LENGTH = 64;
    public static final int MAX_ADDRESS_LENGTH = 45;

    private static final Pattern UNPRINTABLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\u00a7]");

    public static ImportOrigin reportedBy(@Nullable String serverName, @Nullable String observedAddress) {
        String name = serverName == null ? "" : UNPRINTABLE.matcher(serverName).replaceAll("").strip();
        if (name.isEmpty()) {
            throw new ValidationException("The server name is required");
        }
        String address = observedAddress == null || observedAddress.length() > MAX_ADDRESS_LENGTH ? null : observedAddress;
        return new ImportOrigin(CappedText.of(name, MAX_SERVER_NAME_LENGTH), address);
    }
}
