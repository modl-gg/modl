package gg.modl.update;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class HostDescriptor {
    private static final String SEPARATOR = ":";
    private static final String KEY_SEPARATOR = ",";

    private final int api;
    private final ReleaseVersion version;
    private final List<String> keyIds;

    public HostDescriptor(int api, ReleaseVersion version, Collection<String> keyIds) {
        this.api = api;
        this.version = version;
        List<String> sorted = new ArrayList<String>(keyIds);
        Collections.sort(sorted);
        this.keyIds = Collections.unmodifiableList(sorted);
    }

    public static @Nullable HostDescriptor parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String[] parts = text.split(SEPARATOR, -1);
        if (parts.length != 3) {
            return null;
        }
        ReleaseVersion version = ReleaseVersion.tryParse(parts[1]);
        int api;
        try {
            api = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (version == null || api < 1) {
            return null;
        }
        List<String> keyIds = parts[2].isEmpty() ? Collections.<String>emptyList() : Arrays.asList(parts[2].split(KEY_SEPARATOR));
        return new HostDescriptor(api, version, keyIds);
    }

    public int api() {
        return api;
    }

    public ReleaseVersion version() {
        return version;
    }

    public List<String> keyIds() {
        return keyIds;
    }

    public boolean knowsKey(String keyId) {
        return keyIds.contains(keyId);
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder().append(api).append(SEPARATOR).append(version).append(SEPARATOR);
        for (int i = 0; i < keyIds.size(); i++) {
            if (i > 0) {
                text.append(KEY_SEPARATOR);
            }
            text.append(keyIds.get(i));
        }
        return text.toString();
    }
}
