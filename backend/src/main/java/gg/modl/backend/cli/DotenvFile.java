package gg.modl.backend.cli;

import io.github.cdimascio.dotenv.Dotenv;
import io.github.cdimascio.dotenv.DotenvEntry;
import io.github.cdimascio.dotenv.DotenvException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DotenvFile {
    private static final String WORKING_DIRECTORY = "./";

    private DotenvFile() {
    }

    public static Map<String, Object> fromWorkingDirectory() {
        try {
            Dotenv dotenv = Dotenv.configure().directory(WORKING_DIRECTORY).ignoreIfMissing().load();
            Map<String, Object> entries = new LinkedHashMap<>();
            for (DotenvEntry entry : dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE)) {
                entries.put(entry.getKey(), entry.getValue());
            }
            return entries;
        } catch (DotenvException e) {
            throw new InvalidDotenvException("./.env could not be read: " + e.getMessage(), e);
        }
    }
}
