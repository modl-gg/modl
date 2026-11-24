package gg.modl.backend.support;

import static gg.modl.backend.database.jooq.Tables.HOMEPAGE_CARDS;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;
import static gg.modl.backend.database.jooq.Tables.PLAYERS;
import static gg.modl.backend.database.jooq.Tables.PLAYER_NOTES;
import static gg.modl.backend.database.jooq.Tables.PLAYER_USERNAMES;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENTS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_EVIDENCE;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_MODIFICATIONS;
import static gg.modl.backend.database.jooq.Tables.PUNISHMENT_NOTES;
import static gg.modl.backend.database.jooq.Tables.SETTINGS;
import static gg.modl.backend.database.jooq.Tables.STAFF;
import static gg.modl.backend.database.jooq.Tables.STAFF_ROLES;
import static gg.modl.backend.database.jooq.Tables.TICKETS;
import static gg.modl.backend.database.jooq.Tables.TICKET_REPLIES;
import static gg.modl.backend.database.jooq.Tables.TICKET_TAGS;

import gg.modl.backend.database.jooq.tables.records.HomepageCardsRecord;
import gg.modl.backend.database.jooq.tables.records.KnowledgebaseArticlesRecord;
import gg.modl.backend.database.jooq.tables.records.KnowledgebaseCategoriesRecord;
import gg.modl.backend.database.jooq.tables.records.PlayersRecord;
import gg.modl.backend.database.jooq.tables.records.PunishmentsRecord;
import gg.modl.backend.database.jooq.tables.records.StaffRecord;
import gg.modl.backend.database.jooq.tables.records.StaffRolesRecord;
import gg.modl.backend.database.jooq.tables.records.TicketsRecord;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

public final class TestDatabase {
    private static volatile TestDatabase instance;

    private final DSLContext dsl;

    private TestDatabase(DSLContext dsl) {
        this.dsl = dsl;
    }

    public static TestDatabase getInstance() {
        if (instance == null) {
            synchronized (TestDatabase.class) {
                if (instance == null) {
                    instance = new TestDatabase(connect());
                }
            }
        }
        return instance;
    }

    private static DSLContext connect() {
        return TestServerCredentials.usesExternalServer()
            ? DSL.using(TestServerCredentials.databaseUrl(),
                TestServerCredentials.databaseUsername(), TestServerCredentials.databasePassword())
            : InProcessServer.get().dsl();
    }

    public static boolean isAvailable() {
        if (!TestServerCredentials.usesExternalServer()) {
            return true;
        }
        String url = TestServerCredentials.databaseUrl();
        return url != null && !url.isBlank();
    }

    public List<PlayersRecord> players(int limit) {
        return dsl.selectFrom(PLAYERS).orderBy(PLAYERS.CREATED_AT, PLAYERS.ID).limit(limit).fetch();
    }

    public Optional<PlayersRecord> anyPunishedPlayer() {
        return dsl.selectFrom(PLAYERS)
            .whereExists(DSL.selectOne().from(PUNISHMENTS).where(PUNISHMENTS.PLAYER_ID.eq(PLAYERS.ID)))
            .orderBy(PLAYERS.ID)
            .limit(1)
            .fetchOptional();
    }

    public Optional<String> settingsJson(String type) {
        return dsl.select(SETTINGS.DATA_JSON).from(SETTINGS).where(SETTINGS.TYPE.eq(type)).fetchOptional(SETTINGS.DATA_JSON);
    }

    public PlayersRecord findPlayerByUuid(String uuid) {
        return dsl.selectFrom(PLAYERS).where(PLAYERS.MINECRAFT_UUID.eq(uuid)).fetchOne();
    }

    public List<String> usernamesOf(PlayersRecord player) {
        return dsl.select(PLAYER_USERNAMES.USERNAME)
            .from(PLAYER_USERNAMES)
            .where(PLAYER_USERNAMES.PLAYER_ID.eq(player.getId()))
            .fetch(PLAYER_USERNAMES.USERNAME);
    }

    public List<String> notesOf(PlayersRecord player) {
        return dsl.select(PLAYER_NOTES.BODY)
            .from(PLAYER_NOTES)
            .where(PLAYER_NOTES.PLAYER_ID.eq(player.getId()))
            .fetch(PLAYER_NOTES.BODY);
    }

    public PunishmentsRecord findPunishmentInPlayer(String playerUuid, String punishmentId) {
        return dsl.selectFrom(PUNISHMENTS)
            .where(PUNISHMENTS.ID.eq(punishmentId))
            .and(PUNISHMENTS.PLAYER_ID.in(DSL.select(PLAYERS.ID).from(PLAYERS).where(PLAYERS.MINECRAFT_UUID.eq(playerUuid))))
            .fetchOne();
    }

    public List<String> notesOf(PunishmentsRecord punishment) {
        return dsl.select(PUNISHMENT_NOTES.BODY)
            .from(PUNISHMENT_NOTES)
            .where(PUNISHMENT_NOTES.PUNISHMENT_ID.eq(punishment.getId()))
            .fetch(PUNISHMENT_NOTES.BODY);
    }

    public List<String> evidenceUrlsOf(PunishmentsRecord punishment) {
        return dsl.select(PUNISHMENT_EVIDENCE.URL)
            .from(PUNISHMENT_EVIDENCE)
            .where(PUNISHMENT_EVIDENCE.PUNISHMENT_ID.eq(punishment.getId()))
            .fetch(PUNISHMENT_EVIDENCE.URL);
    }

    public List<String> modificationTypesOf(PunishmentsRecord punishment) {
        return dsl.select(PUNISHMENT_MODIFICATIONS.TYPE)
            .from(PUNISHMENT_MODIFICATIONS)
            .where(PUNISHMENT_MODIFICATIONS.PUNISHMENT_ID.eq(punishment.getId()))
            .fetch(PUNISHMENT_MODIFICATIONS.TYPE);
    }

    public TicketsRecord findTicketById(String ticketId) {
        return dsl.selectFrom(TICKETS).where(TICKETS.ID.eq(ticketId)).fetchOne();
    }

    public List<String> replyContentsOf(TicketsRecord ticket) {
        return dsl.select(TICKET_REPLIES.CONTENT)
            .from(TICKET_REPLIES)
            .where(TICKET_REPLIES.TICKET_ID.eq(ticket.getId()))
            .fetch(TICKET_REPLIES.CONTENT);
    }

    public List<String> tagsOf(TicketsRecord ticket) {
        return dsl.select(TICKET_TAGS.TAG)
            .from(TICKET_TAGS)
            .where(TICKET_TAGS.TICKET_ID.eq(ticket.getId()))
            .fetch(TICKET_TAGS.TAG);
    }

    public StaffRecord findStaffByUsername(String username) {
        return dsl.selectFrom(STAFF).where(STAFF.USERNAME.eq(username)).fetchOne();
    }

    public StaffRolesRecord findRoleById(String roleId) {
        return dsl.selectFrom(STAFF_ROLES).where(STAFF_ROLES.ID.eq(roleId)).fetchOne();
    }

    public HomepageCardsRecord findHomepageCardById(String cardId) {
        return dsl.selectFrom(HOMEPAGE_CARDS).where(HOMEPAGE_CARDS.ID.eq(cardId)).fetchOne();
    }

    public KnowledgebaseCategoriesRecord findKbCategoryById(String categoryId) {
        return dsl.selectFrom(KNOWLEDGEBASE_CATEGORIES).where(KNOWLEDGEBASE_CATEGORIES.ID.eq(categoryId)).fetchOne();
    }

    public KnowledgebaseArticlesRecord findKbArticleById(String articleId) {
        return dsl.selectFrom(KNOWLEDGEBASE_ARTICLES).where(KNOWLEDGEBASE_ARTICLES.ID.eq(articleId)).fetchOne();
    }
}
