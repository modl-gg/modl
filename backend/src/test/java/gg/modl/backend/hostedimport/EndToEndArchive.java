package gg.modl.backend.hostedimport;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

public final class EndToEndArchive {
    public static final int PLAYERS = 600;
    public static final int CHAT_LINES = 1_200;
    public static final int NUMBERED_OBJECTS = 30;
    public static final int OBJECTS = NUMBERED_OBJECTS + 3;
    public static final int SKIP_EVERY = 100;
    public static final String SELECTED_STAFF = "staff-1";
    public static final String AUDIT_SOURCE = "hosted-fixture";
    public static final String SERVER_NAME = "Imported Network";
    public static final String CATEGORY = "category-1";
    public static final String ROLE = "role-mods";
    public static final String EVIDENCE = "evidence/Case.png";
    public static final String EVIDENCE_CONTENT = "upper case";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REPLAY = "replays/replay-1.modlreplay";
    private static final String BANNED_PUNISHMENT = "punishment-1";
    private static final String TICKET = "BUG-100001";

    private final Instant recent;
    private final Instant old;
    private final HostedArchiveFixture fixture = new HostedArchiveFixture().adminEmail("previous-owner@example.com");

    private EndToEndArchive(Instant now) {
        this.recent = now.minus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MILLIS);
        this.old = recent.minus(Duration.ofDays(700));
    }

    public static Path write(Path directory) throws IOException {
        EndToEndArchive archive = new EndToEndArchive(Instant.now());
        archive.configuration();
        archive.players();
        archive.records();
        archive.objects();
        return archive.fixture.write(directory);
    }

    private void configuration() {
        line("staffroles", Map.of("_id", ROLE, "name", "Mods", "permissions", List.of("punishment.view"),
            "createdAt", date(old)));
        line("staffs", Map.of("_id", SELECTED_STAFF, "email", "moderator@example.com", "username", "Moderator", "role", ROLE,
            "createdAt", date(old),
            "subscribedTickets", List.of(Map.of("ticketId", TICKET, "subscribedAt", date(recent)))));
        line("staffs", Map.of("_id", "staff-2", "email", "unselected@example.com", "username", "Unselected", "role", ROLE));
        line("settings", Map.of("_id", "settings-general", "type", "general", "version", 1,
            "data", Map.of("serverDisplayName", SERVER_NAME, "defaultLanguage", "en")));
        line("knowledgebasecategories", Map.of("_id", CATEGORY, "name", "Rules", "slug", "rules", "createdAt", date(old)));
        line("knowledgebasearticles", Map.of("_id", "article-1", "title", "Chat rules", "content", "Be kind",
            "categoryId", CATEGORY, "createdAt", date(old)));
        line("knowledgebasearticles", Map.of("_id", "article-2", "title", "Orphan", "content", "No category",
            "createdAt", date(old)));
        line("homepagecards", Map.of("_id", "card-1", "title", "Rules", "actionType", "category_dropdown",
            "categoryId", CATEGORY, "createdAt", date(old)));
    }

    private void players() {
        for (int index = 0; index < PLAYERS; index++) {
            if (index % SKIP_EVERY == 0) {
                line("players", Map.of("_id", "invalid-" + index, "minecraftUuid", "not-a-uuid"));
            }
            if (index == 0) {
                bannedPlayer();
            } else {
                line("players", Map.of("_id", playerId(index), "minecraftUuid", uuid(index),
                    "usernames", List.of(Map.of("username", "Player" + index, "date", date(old))),
                    "data", Map.of("firstJoin", date(old))));
            }
        }
    }

    private void bannedPlayer() {
        Map<String, Object> punishment = Map.of(
            "id", BANNED_PUNISHMENT,
            "typeOrdinal", 2,
            "issuerId", SELECTED_STAFF,
            "issuerName", "Moderator",
            "issued", date(old),
            "started", date(old),
            "notes", List.of(Map.of("id", "note-1", "text", "Seen in the replay", "date", date(old))),
            "evidence", List.of(Map.of("url", HostedArchiveFixture.BASE_URL + HostedArchiveFixture.PREFIX + EVIDENCE,
                "type", "link", "uploadedAt", date(old))),
            "attachedTicketIds", List.of(TICKET),
            "data", Map.of("reason", "Cheating", "enforcementCategory", "BAN"));
        line("players", Map.of(
            "_id", playerId(0),
            "minecraftUuid", uuid(0),
            "usernames", List.of(Map.of("username", "Player0", "date", date(old))),
            "ipAddresses", List.of(Map.of("ipAddress", "198.51.100.9", "firstLogin", date(old), "logins", List.of(date(old)))),
            "punishments", List.of(punishment),
            "data", Map.of(
                "firstJoin", date(old),
                "linkedAccounts", List.of(uuid(1)),
                "pendingNotifications", List.of(Map.of("id", "notification-1", "message", "Welcome back",
                    "timestamp", int64(recent.toEpochMilli()))))));
    }

    private void records() {
        line("tickets", Map.of("_id", TICKET, "type", "bug", "status", "open", "subject", "A bug", "creatorUuid", uuid(1),
            "created", date(recent), "updatedAt", date(recent), "replayId", "replay-1",
            "replies", List.of(Map.of("_id", "reply-1", "content", "Looking", "staff", true, "created", date(recent)))));
        line("replays", Map.of("_id", "replay-1", "status", "COMPLETE", "targetUuid", uuid(0), "createdAt", date(recent),
            "storageKey", HostedArchiveFixture.PREFIX + REPLAY));
        for (int index = 0; index < CHAT_LINES; index++) {
            if (index % (2 * SKIP_EVERY) == 0) {
                line("chat_logs", Map.of("uuid", "not-a-uuid", "message", "lost", "timestamp", int64(old.toEpochMilli())));
            }
            line("chat_logs", Map.of("uuid", uuid(index % PLAYERS), "username", "Player" + index % PLAYERS,
                "message", "message " + index, "timestamp", int64(old.plusSeconds(index).toEpochMilli())));
        }
        line("command_logs", Map.of("uuid", uuid(0), "username", "Player0", "command", "/login hunter2",
            "timestamp", int64(recent.toEpochMilli())));
        line("logs", Map.of("source", AUDIT_SOURCE, "description", "Banned Player0", "created", date(recent),
            "metadata", Map.of("playerId", playerId(0), "punishmentId", BANNED_PUNISHMENT)));
    }

    private void objects() {
        for (int index = 0; index < NUMBERED_OBJECTS; index++) {
            fixture.object("evidence/file-%02d.png".formatted(index), "content " + index);
        }
        fixture.object(EVIDENCE, EVIDENCE_CONTENT).object(EVIDENCE.toLowerCase(Locale.ROOT), "lower case").object(REPLAY, "MODL");
    }

    private void line(String collection, Map<String, ?> document) {
        fixture.line(collection, JSON.writeValueAsString(document));
    }

    private static String playerId(int index) {
        return "player-" + index;
    }

    private static String uuid(int index) {
        return "0b7e8a3c-6c55-4b3a-9d8a-%012d".formatted(index);
    }

    private static Map<String, String> date(Instant instant) {
        return Map.of("$date", instant.toString());
    }

    private static Map<String, String> int64(long value) {
        return Map.of("$numberLong", Long.toString(value));
    }
}
