package gg.modl.backend.migration.service;

import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_IPS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.infrastructure.util.IpAddresses;
import gg.modl.backend.migration.validation.MigrationValidator;
import gg.modl.backend.player.data.IPEntry;
import gg.modl.backend.player.data.LegacyNoteText;
import gg.modl.backend.player.data.NoteEntry;
import gg.modl.backend.player.data.Player;
import gg.modl.backend.player.data.PlayerDataKeys;
import gg.modl.backend.player.data.PlayerNoteKind;
import gg.modl.backend.player.data.UsernameEntry;
import gg.modl.backend.player.data.punishment.Punishment;
import gg.modl.backend.settings.data.PunishmentType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public class MigrationRecordMapper {
    private static final int MAX_NOTE_LENGTH = 5000;

    private final MigrationValidator validator;
    private final ImportedValues values;
    private final ImportedPunishments importedPunishments;

    public MigrationRecordMapper(MigrationValidator validator) {
        this.validator = validator;
        this.values = new ImportedValues(validator);
        this.importedPunishments = new ImportedPunishments(validator, values);
    }

    public Player toPlayer(String uuid, Map<?, ?> source, Map<Integer, PunishmentType> typesByOrdinal, Instant importedAt) {
        UUID playerUuid = UUID.fromString(uuid);
        Object addresses = source.get("ipAddresses") != null ? source.get("ipAddresses") : source.get("ipList");
        return Player.builder()
            .minecraftUuid(playerUuid)
            .usernames(usernames(source.get("usernames")))
            .notes(notes(source.get("notes")))
            .ipAddresses(addresses(addresses, importedAt))
            .punishments(punishments(playerUuid, source.get("punishments"), typesByOrdinal, importedAt))
            .data(playerData(source.get("data")))
            .build();
    }

    private List<UsernameEntry> usernames(@Nullable Object source) {
        List<UsernameEntry> result = new ArrayList<>();
        for (Object item : ImportedValues.items(source)) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String username = values.label(map.get("username"), TextLimits.limit(PLAYER_USERNAMES.USERNAME));
            Instant date = validator.parseDate(map.get("date"));
            if (username != null && date != null) {
                result.add(new UsernameEntry(username, date));
            }
        }
        result.sort(Comparator.comparing(UsernameEntry::date));
        return result;
    }

    private List<NoteEntry> notes(@Nullable Object source) {
        List<NoteEntry> result = new ArrayList<>();
        for (Object item : ImportedValues.items(source)) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String text = values.text(map.get("text"), MAX_NOTE_LENGTH);
            Instant date = validator.parseDate(map.get("date"));
            String issuerName = values.label(map.get("issuerName"), TextLimits.limit(PLAYER_NOTES.ISSUER_NAME));
            String issuerId = values.identifier(map.get("issuerId"), TextLimits.limit(PLAYER_NOTES.ISSUER_ID));
            String issuer = issuerName != null ? issuerName : issuerId;
            if (text != null && date != null && issuer != null) {
                String sourceId = values.label(map.get("id"), Integer.MAX_VALUE);
                LegacyNoteText note = importedNote(map.get("kind"), text);
                result.add(new NoteEntry(noteId(sourceId, text, date, issuer),
                    note.text(), date, issuerName, issuerId, note.kind()));
            }
        }
        return result;
    }

    private static LegacyNoteText importedNote(@Nullable Object kind, String text) {
        if (kind instanceof String named) {
            for (PlayerNoteKind candidate : PlayerNoteKind.values()) {
                if (candidate.name().equalsIgnoreCase(named.trim())) {
                    return new LegacyNoteText(candidate, text);
                }
            }
        }
        return LegacyNoteText.of(text);
    }

    private List<IPEntry> addresses(@Nullable Object source, Instant importedAt) {
        List<IPEntry> result = new ArrayList<>();
        for (Object item : ImportedValues.items(source)) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String address = map.get("ipAddress") instanceof String text ? IpAddresses.literalOrNull(text) : null;
            if (address == null) {
                continue;
            }
            List<Instant> logins = new ArrayList<>();
            for (Object login : ImportedValues.items(map.get("logins"))) {
                Instant seenAt = validator.parseDate(login);
                if (seenAt != null) {
                    logins.add(seenAt);
                }
            }
            List<Instant> sightings = new ArrayList<>(logins);
            Instant firstLogin = validator.parseDate(map.get("firstLogin"));
            if (firstLogin != null) {
                sightings.add(firstLogin);
            }
            if (sightings.isEmpty()) {
                sightings.add(importedAt);
            }
            result.add(IPEntry.builder()
                .ipAddress(address)
                .country(values.label(map.get("country"), TextLimits.limit(PLAYER_IPS.COUNTRY)))
                .region(values.label(map.get("region"), TextLimits.limit(PLAYER_IPS.REGION)))
                .asn(values.label(map.get("asn"), TextLimits.limit(PLAYER_IPS.ASN)))
                .proxy(Boolean.TRUE.equals(map.get("proxy")))
                .hosting(Boolean.TRUE.equals(map.get("hosting")))
                .firstLogin(sightings.stream().min(Comparator.naturalOrder()).orElseThrow())
                .lastLogin(sightings.stream().max(Comparator.naturalOrder()).orElseThrow())
                .loginCount(Math.max(logins.size(), 1))
                .build());
        }
        return result;
    }

    private List<Punishment> punishments(UUID playerUuid, @Nullable Object source,
                                         Map<Integer, PunishmentType> typesByOrdinal, Instant importedAt) {
        List<Punishment> result = new ArrayList<>();
        for (Object item : ImportedValues.items(source)) {
            if (item instanceof Map<?, ?> map) {
                Punishment punishment = importedPunishments.toPunishment(playerUuid, map, typesByOrdinal, importedAt);
                if (punishment != null) {
                    result.add(punishment);
                }
            }
        }
        return result;
    }

    private Map<String, Object> playerData(@Nullable Object source) {
        Map<String, Object> data = ImportedValues.entries(source);
        data.remove(PlayerDataKeys.IS_ONLINE);
        data.remove(PlayerDataKeys.LINKED_ACCOUNTS);
        data.remove(PlayerDataKeys.PENDING_NOTIFICATIONS);
        for (String key : List.of(PlayerDataKeys.FIRST_JOIN, PlayerDataKeys.LAST_LOGIN, PlayerDataKeys.LAST_LOGOUT,
            PlayerDataKeys.LAST_LINKED_UPDATE)) {
            ImportedValues.putOrRemove(data, key, validator.parseDate(data.get(key)));
        }
        ImportedValues.putOrRemove(data, PlayerDataKeys.TOTAL_PLAYTIME_SECONDS,
            ImportedValues.wholeNumber(data.get(PlayerDataKeys.TOTAL_PLAYTIME_SECONDS)));
        ImportedValues.putOrRemove(data, PlayerDataKeys.LAST_SERVER,
            values.label(data.get(PlayerDataKeys.LAST_SERVER), TextLimits.limit(PLAYERS.LAST_SERVER_NAME)));
        ImportedValues.putOrRemove(data, PlayerDataKeys.LAST_SKIN_HASH,
            values.label(data.get(PlayerDataKeys.LAST_SKIN_HASH), TextLimits.limit(PLAYERS.LAST_SKIN_HASH)));
        return data;
    }

    private static String noteId(@Nullable String sourceId, String text, Instant date, String issuer) {
        if (sourceId != null && TextLimits.canMatch(PLAYER_NOTES.ID, sourceId)) {
            return sourceId;
        }
        String identity = sourceId != null ? sourceId : text + "|" + date.toEpochMilli() + "|" + issuer;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
