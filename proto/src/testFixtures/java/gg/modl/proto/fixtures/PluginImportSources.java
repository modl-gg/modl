package gg.modl.proto.fixtures;

import com.buf.validate.ValidateProto;
import gg.modl.proto.modl.v1.StartImportJobRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PluginImportSources {
    private static final String FILE = "plugin-import-sources.txt";
    private static final String SEPARATOR = "\t";

    private PluginImportSources() {
    }

    public static Map<String, String> displayNamesById() {
        Map<String, String> names = new LinkedHashMap<>();
        try (InputStream input = PluginImportSources.class.getResourceAsStream(FILE)) {
            if (input == null) throw new IllegalStateException("The fixture " + FILE + " is missing");
            BufferedReader lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.isEmpty()) continue;
                String[] parts = line.split(SEPARATOR, 2);
                if (parts.length != 2) throw new IllegalStateException("Malformed line in " + FILE + ": " + line);
                names.put(parts[0], parts[1]);
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return Collections.unmodifiableMap(names);
    }

    public static List<String> wireIds() {
        return Collections.unmodifiableList(new ArrayList<>(StartImportJobRequest.getDescriptor().findFieldByName("type")
                .getOptions().getExtension(ValidateProto.field).getString().getInList()));
    }
}
