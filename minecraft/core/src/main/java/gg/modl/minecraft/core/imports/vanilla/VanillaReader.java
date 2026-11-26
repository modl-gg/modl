package gg.modl.minecraft.core.imports.vanilla;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.modl.minecraft.core.imports.source.PlayerRecords;
import gg.modl.minecraft.core.imports.source.PunishmentKind;
import gg.modl.minecraft.core.imports.source.PunishmentScope;
import gg.modl.minecraft.core.imports.source.SourceContext;
import gg.modl.minecraft.core.imports.source.SourceException;
import gg.modl.minecraft.core.imports.source.SourceInventory;
import gg.modl.minecraft.core.imports.source.SourcePlayer;
import gg.modl.minecraft.core.imports.source.SourcePunishment;
import gg.modl.minecraft.core.imports.source.SourceReader;
import gg.modl.minecraft.core.imports.support.ConfigFile;
import gg.modl.minecraft.core.imports.support.InventoryCounter;
import gg.modl.minecraft.core.imports.support.LocalTimes;
import gg.modl.minecraft.core.imports.support.PlayerHistory;
import gg.modl.minecraft.core.imports.support.PlayerKeys;
import gg.modl.minecraft.core.imports.support.PlayerLimit;
import gg.modl.minecraft.core.imports.support.SoleOwners;
import gg.modl.minecraft.core.imports.support.SourceSupport;
import gg.modl.minecraft.core.util.Constants;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class VanillaReader implements SourceReader {
    private static final String FOREVER = "forever";
    private static final String UNKNOWN_SOURCE = "(unknown)";
    private static final String USER_FILE = ".yml";
    private static final String NO_DATE = "0";

    private final SourceContext context;
    private final Path serverRoot;
    private final Path essentialsUsers;
    private volatile boolean cancelled;
    private @Nullable Summary summary;
    private @Nullable DirectoryStream<Path> streaming;

    VanillaReader(SourceContext context, Path serverRoot, Path essentialsUsers) {
        this.context = context;
        this.serverRoot = serverRoot;
        this.essentialsUsers = essentialsUsers;
    }

    @Override
    public SourceInventory inventory() throws SourceException {
        Summary found = summary();
        int players = found.users + found.bannedOnly();
        PlayerLimit.require(players);
        return found.counts.toInventory(players);
    }

    @Override
    public long historyEntriesBefore(long epochMillis) throws SourceException {
        return summary().logins.countBefore(epochMillis);
    }

    @Override
    public PlayerRecords players() throws SourceException {
        Summary found = summary();
        Map<String, List<SourcePunishment>> bans = new LinkedHashMap<>(found.bans);
        Iterator<Path> users = Files.isDirectory(essentialsUsers) ? openUsers().iterator() : new ArrayList<Path>().iterator();
        return new PlayerRecords() {
            private @Nullable Iterator<Map.Entry<String, List<SourcePunishment>>> remaining;

            @Override
            public @Nullable SourcePlayer next() throws SourceException {
                while (users.hasNext()) {
                    requireRunning();
                    Path file = users.next();
                    String uuid = uuidOf(file);
                    if (uuid == null) continue;
                    SourcePlayer player = userPlayer(uuid, file, bans.remove(uuid));
                    if (player != null) return player;
                }
                Iterator<Map.Entry<String, List<SourcePunishment>>> rest = remaining;
                if (rest == null) {
                    rest = bans.entrySet().iterator();
                    remaining = rest;
                }
                if (!rest.hasNext()) return null;
                Map.Entry<String, List<SourcePunishment>> entry = rest.next();
                PlayerHistory history = new PlayerHistory();
                entry.getValue().forEach(history::punished);
                return history.toPlayer(entry.getKey());
            }
        };
    }

    private Summary summary() throws SourceException {
        Summary existing = summary;
        if (existing != null) return existing;
        Summary created = new Summary();
        List<JsonObject> addressBans = new ArrayList<>();
        readEntries(serverRoot.resolve(VanillaSource.BANNED_IPS), addressBans::add);
        Set<String> bannedAddresses = new HashSet<>();
        for (JsonObject entry : addressBans) {
            String address = PlayerKeys.addressOrNull(text(entry, "ip"));
            if (address != null) bannedAddresses.add(address);
        }
        if (Files.isDirectory(essentialsUsers)) {
            for (Path file : openUsers()) {
                requireRunning();
                String uuid = uuidOf(file);
                ConfigFile user = uuid == null ? null : readUser(file);
                if (uuid == null || user == null) continue;
                created.users++;
                PlayerLimit.require(created.users);
                long login = login(user);
                String address = PlayerKeys.addressOrNull(firstText(user, "ip-address", "ipAddress"));
                if (address != null && bannedAddresses.contains(address)) created.owners.used(address, uuid);
                if (address != null || firstText(user, "last-account-name", "lastAccountName") != null) created.logins.add(login);
                if (mute(uuid, user) != null) created.counts.counted(PunishmentKind.MUTE);
            }
            closeUsers();
        }
        readEntries(serverRoot.resolve(VanillaSource.BANNED_PLAYERS), entry -> playerBan(created, entry));
        for (JsonObject entry : addressBans) addressBan(created, entry);
        summary = created;
        return created;
    }

    private @Nullable SourcePlayer userPlayer(String uuid, Path file, @Nullable List<SourcePunishment> bans) {
        ConfigFile user = readUser(file);
        PlayerHistory history = new PlayerHistory();
        if (bans != null) bans.forEach(history::punished);
        if (user != null) {
            long login = login(user);
            history.seen(firstText(user, "last-account-name", "lastAccountName"),
                    PlayerKeys.addressOrNull(firstText(user, "ip-address", "ipAddress")), login);
            SourcePunishment mute = mute(uuid, user);
            if (mute != null) history.punished(mute);
        }
        SourcePlayer player = history.toPlayer(uuid);
        return player.hasHistory() || !player.getPunishments().isEmpty() ? player : null;
    }

    private @Nullable SourcePunishment mute(String uuid, ConfigFile user) {
        if (!user.bool("muted", false)) return null;
        long now = context.now();
        long until = number(user, "timestamps.mute", 0);
        if (until > 0 && until <= now) return null;
        String reason = firstText(user, "mute-reason", "muteReason");
        return SourcePunishment.builder()
                .id("essentials-mute-" + uuid.replace("-", "") + "-" + Long.toString(until, Character.MAX_RADIX))
                .kind(PunishmentKind.MUTE)
                .reason(SourceSupport.trimmedOrNull(reason))
                .issuedAt(now)
                .duration(until > 0 ? until - now : 0)
                .inForce(true)
                .build();
    }

    private void playerBan(Summary found, JsonObject entry) {
        found.counts.counted(PunishmentKind.BAN);
        String uuid = PlayerKeys.uuidOrNull(text(entry, "uuid"));
        if (uuid == null) {
            found.counts.withoutAccount();
            return;
        }
        found.bans.computeIfAbsent(uuid, unseen -> new ArrayList<>())
                .add(ban(entry, "vanilla-ban-" + uuid.replace("-", ""), PunishmentScope.ACCOUNT));
    }

    private void addressBan(Summary found, JsonObject entry) {
        found.counts.counted(PunishmentKind.BAN);
        found.counts.scoped(PunishmentScope.IP_WIDE);
        String address = PlayerKeys.addressOrNull(text(entry, "ip"));
        String owner = found.owners.soleOwner(address);
        byte[] raw = PlayerKeys.bytesOfAddress(address);
        if (owner == null || raw == null) {
            found.counts.withoutAccount();
            return;
        }
        StringBuilder hex = new StringBuilder();
        for (byte part : raw) hex.append(String.format(Locale.ROOT, "%02x", part));
        found.bans.computeIfAbsent(owner, unseen -> new ArrayList<>())
                .add(ban(entry, "vanilla-ipban-" + hex, PunishmentScope.IP_WIDE).attributedToTheOnlyAccountOfItsAddress());
    }

    private SourcePunishment ban(JsonObject entry, String idPrefix, PunishmentScope scope) {
        long now = context.now();
        Long created = LocalTimes.withOffset(text(entry, "created"));
        String expiresText = text(entry, "expires");
        Long expires = expiresText == null || FOREVER.equalsIgnoreCase(expiresText.trim()) ? null
                : LocalTimes.withOffset(expiresText);
        long issued = created != null ? created : now;
        boolean lapsed = expires != null && expires <= now;
        return SourcePunishment.builder()
                .id(idPrefix + "-" + (created != null ? Long.toString(created, Character.MAX_RADIX) : NO_DATE))
                .kind(PunishmentKind.BAN)
                .scope(scope)
                .reason(SourceSupport.trimmedOrNull(text(entry, "reason")))
                .issuerName(issuer(text(entry, "source")))
                .issuedAt(issued)
                .duration(expires != null ? SourcePunishment.durationBetween(issued, expires) : 0)
                .inForce(!lapsed)
                .build();
    }

    private static @Nullable String issuer(@Nullable String source) {
        if (source == null || UNKNOWN_SOURCE.equals(source.trim().toLowerCase(Locale.ROOT))) return null;
        String trimmed = source.trim();
        if ("server".equalsIgnoreCase(trimmed) || "rcon".equalsIgnoreCase(trimmed)) return Constants.DEFAULT_CONSOLE_NAME;
        return SourceSupport.issuer(trimmed);
    }

    @SuppressWarnings("StreamResourceLeak")
    private DirectoryStream<Path> openUsers() throws SourceException {
        closeUsers();
        try {
            DirectoryStream<Path> files = Files.newDirectoryStream(essentialsUsers, "*" + USER_FILE);
            streaming = files;
            return files;
        } catch (IOException failure) {
            throw SourceException.failed(failure);
        }
    }

    private void closeUsers() {
        DirectoryStream<Path> files = streaming;
        streaming = null;
        if (files == null) return;
        try {
            files.close();
        } catch (IOException ignored) {
            context.getLogger().debug("The EssentialsX user folder could not be closed");
        }
    }

    private void requireRunning() throws SourceException {
        if (cancelled) throw SourceException.cancelled();
    }

    private @Nullable ConfigFile readUser(Path file) {
        try {
            return ConfigFile.read(file);
        } catch (SourceException unreadable) {
            context.getLogger().debug("Skipped the unreadable EssentialsX user file " + file + ": " + unreadable.getMessage());
            return null;
        }
    }

    private long login(ConfigFile user) {
        return number(user, "timestamps.login", number(user, "timestamps.logout", context.now()));
    }

    private static @Nullable String uuidOf(Path file) {
        String name = String.valueOf(file.getFileName());
        return PlayerKeys.uuidOrNull(name.substring(0, Math.max(0, name.length() - USER_FILE.length())));
    }

    private static void readEntries(Path file, Consumer<JsonObject> reader) throws SourceException {
        if (!Files.isRegularFile(file)) return;
        try (Reader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(input);
            if (!root.isJsonArray()) throw SourceException.failed(file + " is not a JSON list");
            for (JsonElement entry : root.getAsJsonArray()) {
                if (entry.isJsonObject()) reader.accept(entry.getAsJsonObject());
            }
        } catch (IOException | RuntimeException unreadable) {
            throw SourceException.failed("Could not read " + file);
        }
    }

    private static @Nullable String text(JsonObject entry, String key) {
        JsonElement value = entry.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static @Nullable String firstText(ConfigFile user, String... paths) {
        for (String path : Arrays.asList(paths)) {
            Object value = user.value(path);
            if (value != null && SourceSupport.hasText(String.valueOf(value))) return String.valueOf(value).trim();
        }
        return null;
    }

    private static long number(ConfigFile user, String path, long fallback) {
        Object value = user.value(path);
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException notNumber) {
            return fallback;
        }
    }

    @Override
    public boolean isReusable() {
        return !cancelled;
    }

    @Override
    public void cancelRunning() {
        cancelled = true;
    }

    @Override
    public void close() {
        closeUsers();
        summary = null;
    }

    private final class Summary {
        private final InventoryCounter counts = new InventoryCounter();
        private final SoleOwners owners = new SoleOwners();
        private final Map<String, List<SourcePunishment>> bans = new LinkedHashMap<>();
        private final Logins logins = new Logins();
        private int users;

        private int bannedOnly() {
            int only = 0;
            for (String uuid : bans.keySet()) {
                if (!Files.isRegularFile(essentialsUsers.resolve(uuid + USER_FILE))) only++;
            }
            return only;
        }
    }

    private static final class Logins {
        private static final int INITIAL = 1024;

        private long[] at = new long[INITIAL];
        private int size;

        private void add(long login) {
            if (size == at.length) at = Arrays.copyOf(at, size * 2);
            at[size++] = login;
        }

        private long countBefore(long epochMillis) {
            long before = 0;
            for (int index = 0; index < size; index++) {
                if (at[index] < epochMillis) before++;
            }
            return before;
        }
    }
}
