package gg.modl.backend.homepage.repository;

import static gg.modl.backend.database.jooq.Tables.HOMEPAGE_CARDS;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.database.ValueTooLongException;
import gg.modl.backend.database.jooq.tables.records.KnowledgebaseCategoriesRecord;
import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.io.TempDir;

class JooqHomepageCardRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant LATER = NOW.plusSeconds(60);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void aCardRoundTripsWithEveryColumn(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqHomepageCardRepository repository = new JooqHomepageCardRepository(database.dsl());
            insertCategory(database, "category");
            HomepageCard card = HomepageCard.builder()
                .id("card")
                .title("Règles 📘")
                .description("description")
                .icon("BookOpen")
                .iconColor("#8b5cf6")
                .actionType("category_dropdown")
                .actionUrl("/knowledgebase")
                .categoryId("category")
                .backgroundColor("#ffffff")
                .ordinal(4)
                .isEnabled(false)
                .createdAt(NOW)
                .updatedAt(LATER)
                .build();

            database.unitOfWork().write(() -> repository.insert(card));

            assertThat(repository.findById("card")).contains(card);
            assertThat(repository.hasAny()).isTrue();
            assertThat(repository.findById("missing")).isEmpty();
        }
    }

    @SqlContractTest
    void cardsWithEqualOrdinalsComeBackByCreationThenId(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqHomepageCardRepository repository = new JooqHomepageCardRepository(database.dsl());
            assertThat(repository.hasAny()).isFalse();
            assertThat(repository.findMaxOrdinal()).isEqualTo(-1);
            database.unitOfWork().write(() -> {
                repository.insert(card("late", 1, LATER, true, null));
                repository.insert(card("b", 1, NOW, false, null));
                repository.insert(card("first", 0, LATER, true, null));
                repository.insert(card("a", 1, NOW, true, null));
            });

            assertThat(repository.findAllOrdered()).extracting(HomepageCard::getId).containsExactly("first", "a", "b", "late");
            assertThat(repository.findVisibleOrdered()).extracting(HomepageCard::getId).containsExactly("first", "a", "late");
            assertThat(repository.findMaxOrdinal()).isEqualTo(1);
        }
    }

    @SqlContractTest
    void emptyBlankAndDanglingCategoryIdsAreStoredAsNull(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqHomepageCardRepository repository = new JooqHomepageCardRepository(database.dsl());
            insertCategory(database, "category");
            List<String> unusable = List.of("", "   ", "deleted", "x".repeat(64));
            database.unitOfWork().write(() -> {
                IntStream.range(0, unusable.size())
                    .forEach(index -> repository.insert(card("card-" + index, index, NOW, true, unusable.get(index))));
                repository.insert(card("linked", 9, NOW, true, "category"));
            });

            assertThat(database.dsl().fetchCount(HOMEPAGE_CARDS, HOMEPAGE_CARDS.CATEGORY_ID.isNull())).isEqualTo(unusable.size());
            assertThat(repository.findById("linked")).get().extracting(HomepageCard::getCategoryId).isEqualTo("category");

            for (String categoryId : unusable) {
                database.unitOfWork().write(() -> repository.update("linked", linkTo("category")));
                Optional<HomepageCard> updated = database.unitOfWork().write(() -> repository.update("linked", linkTo(categoryId)));
                assertThat(updated).get().extracting(HomepageCard::getCategoryId).isNull();
            }
        }
    }

    @SqlContractTest
    void deletingACategoryClearsTheCardsThatPointAtIt(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqHomepageCardRepository repository = new JooqHomepageCardRepository(database.dsl());
            insertCategory(database, "category");
            database.unitOfWork().write(() -> repository.insert(card("card", 0, NOW, true, "category")));

            database.unitOfWork().write(() -> database.dsl().deleteFrom(KNOWLEDGEBASE_CATEGORIES).execute());

            assertThat(repository.findById("card")).get().extracting(HomepageCard::getCategoryId).isNull();
        }
    }

    @SqlContractTest
    void anUpdateChangesOnlyThePatchedColumns(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqHomepageCardRepository repository = new JooqHomepageCardRepository(database.dsl());
            insertCategory(database, "category");
            HomepageCard card = card("card", 3, NOW, true, "category");
            database.unitOfWork().write(() -> repository.insert(card));

            HomepageCardPatch patch = HomepageCardPatch.builder().description("changed").enabled(false).updatedAt(LATER).build();
            Optional<HomepageCard> updated = database.unitOfWork().write(() -> repository.update("card", patch));

            card.setDescription("changed");
            card.setEnabled(false);
            card.setUpdatedAt(LATER);
            assertThat(updated).contains(card);
            assertThat(repository.findById("card")).contains(card);
            Optional<HomepageCard> missing = database.unitOfWork().write(() -> repository.update("missing", patch));
            assertThat(missing).isEmpty();
        }
    }

    @SqlContractTest
    void reorderFollowsTheGivenListAndLeavesUnlistedCardsAlone(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqHomepageCardRepository repository = new JooqHomepageCardRepository(database.dsl());
            database.unitOfWork().write(() -> {
                repository.insert(card("a", 0, NOW, true, null));
                repository.insert(card("b", 1, NOW, true, null));
                repository.insert(card("c", 2, NOW, true, null));
                repository.insert(card("unlisted", 7, NOW, true, null));
            });

            database.unitOfWork().write(() -> repository.reorder(List.of("c", "missing", "a", "b")));

            assertThat(repository.findAllOrdered())
                .extracting(HomepageCard::getId, HomepageCard::getOrdinal)
                .containsExactly(tuple("c", 0), tuple("a", 2), tuple("b", 3), tuple("unlisted", 7));
        }
    }

    @SqlContractTest
    void aDeleteReportsWhetherTheCardExisted(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqHomepageCardRepository repository = new JooqHomepageCardRepository(database.dsl());
            database.unitOfWork().write(() -> repository.insert(card("card", 0, NOW, true, null)));

            assertThat(database.unitOfWork().write(() -> repository.deleteById("card")).booleanValue()).isTrue();
            assertThat(database.unitOfWork().write(() -> repository.deleteById("card")).booleanValue()).isFalse();
        }
    }

    @SqlContractTest
    void lengthsAreCountedInCodePoints(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqHomepageCardRepository repository = new JooqHomepageCardRepository(database.dsl());
            String widest = "📘".repeat(TextLimits.limit(HOMEPAGE_CARDS.TITLE));
            HomepageCard fitting = card("fitting", 0, NOW, true, null);
            fitting.setTitle(widest);
            HomepageCard tooLong = card("too-long", 0, NOW, true, null);
            tooLong.setTitle(widest + "x");

            database.unitOfWork().write(() -> repository.insert(fitting));

            assertThat(repository.findById("fitting")).contains(fitting);
            assertThatThrownBy(() -> database.unitOfWork().write(() -> repository.insert(tooLong)))
                .isInstanceOf(ValueTooLongException.class);
        }
    }

    private static HomepageCardPatch linkTo(String categoryId) {
        return HomepageCardPatch.builder().categoryId(categoryId).updatedAt(LATER).build();
    }

    private static HomepageCard card(String id, int ordinal, Instant createdAt, boolean enabled, @Nullable String categoryId) {
        return HomepageCard.builder()
            .id(id)
            .title("Card " + id)
            .categoryId(categoryId)
            .ordinal(ordinal)
            .isEnabled(enabled)
            .createdAt(createdAt)
            .updatedAt(createdAt)
            .build();
    }

    private static void insertCategory(SqlTestDatabase database, String id) {
        KnowledgebaseCategoriesRecord row = new KnowledgebaseCategoriesRecord();
        row.setId(id);
        row.setName(id);
        row.setSlug(id);
        row.setCreatedAt(NOW);
        row.setUpdatedAt(NOW);
        database.unitOfWork().write(() -> database.dsl().insertInto(KNOWLEDGEBASE_CATEGORIES).set(row).execute());
    }
}
