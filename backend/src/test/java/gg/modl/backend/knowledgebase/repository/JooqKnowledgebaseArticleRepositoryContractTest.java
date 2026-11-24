package gg.modl.backend.knowledgebase.repository;

import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gg.modl.backend.database.ConcurrentUpdateException;
import gg.modl.backend.database.ReferenceViolationException;
import gg.modl.backend.database.jooq.tables.records.KnowledgebaseCategoriesRecord;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.io.TempDir;

class JooqKnowledgebaseArticleRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final Instant LATER = NOW.plusSeconds(60);

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void anArticleRoundTripsWithLongUnicodeContent(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseArticleRepository repository = repository(database);
            insertCategory(database, "category");
            KnowledgebaseArticle article = article("article", "category", 3, NOW, false);
            article.setContent("📘".repeat(50_000));

            database.unitOfWork().write(() -> repository.insert(article));

            assertThat(repository.findById("article")).contains(article);
            assertThat(repository.findBySlug("article")).contains(article);
            assertThat(repository.findBySlug("missing")).isEmpty();
        }
    }

    @SqlContractTest
    void articlesWithEqualOrdinalsComeBackByCreationThenId(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseArticleRepository repository = repository(database);
            insertCategory(database, "one");
            insertCategory(database, "two");
            database.unitOfWork().write(() -> {
                repository.insert(article("late", "one", 1, LATER, true));
                repository.insert(article("b", "one", 1, NOW, true));
                repository.insert(article("hidden", "one", 0, NOW, false));
                repository.insert(article("a", "one", 1, NOW, true));
                repository.insert(article("other", "two", 5, NOW, true));
            });

            assertThat(repository.findByCategoryOrdered("one")).extracting(KnowledgebaseArticle::getId)
                .containsExactly("hidden", "a", "b", "late");
            assertThat(repository.findVisibleByCategoryOrdered("one")).extracting(KnowledgebaseArticle::getId)
                .containsExactly("a", "b", "late");
            assertThat(repository.findVisibleByCategoryIdsOrdered(List.of("two", "one"))).extracting(KnowledgebaseArticle::getId)
                .containsExactly("a", "b", "late", "other");
            assertThat(repository.findVisibleByCategoryIdsOrdered(List.of())).isEmpty();
            assertThat(repository.findAll()).extracting(KnowledgebaseArticle::getId)
                .containsExactly("hidden", "a", "b", "late", "other");
            assertThat(repository.findMaxOrdinalInCategory("one")).isEqualTo(1);
            assertThat(repository.findMaxOrdinalInCategory("empty")).isEqualTo(-1);
        }
    }

    @SqlContractTest
    void aSlugIsTakenForEveryArticleButItself(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseArticleRepository repository = repository(database);
            insertCategory(database, "category");
            database.unitOfWork().write(() -> repository.insert(article("taken", "category", 0, NOW, true)));
            KnowledgebaseArticle second = article("second", "category", 1, NOW, true);
            second.setSlug("taken");

            assertThat(repository.existsBySlug("taken", null)).isTrue();
            assertThat(repository.existsBySlug("taken", "taken")).isFalse();
            assertThat(repository.existsBySlug("taken", "second")).isTrue();
            assertThat(repository.existsBySlug("free", null)).isFalse();
            assertThatThrownBy(() -> database.unitOfWork().write(() -> repository.insert(second)))
                .isInstanceOf(ConcurrentUpdateException.class);
            assertThat(database.dsl().fetchCount(KNOWLEDGEBASE_ARTICLES)).isEqualTo(1);
        }
    }

    @SqlContractTest
    void anArticleNeedsAnExistingCategory(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseArticleRepository repository = repository(database);

            assertThatThrownBy(() -> database.unitOfWork().write(() -> repository.insert(article("a", "missing", 0, NOW, true))))
                .isInstanceOf(ReferenceViolationException.class);
        }
    }

    @SqlContractTest
    void anUpdateChangesOnlyThePatchedColumns(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseArticleRepository repository = repository(database);
            insertCategory(database, "category");
            KnowledgebaseArticle article = article("article", "category", 4, NOW, true);
            database.unitOfWork().write(() -> repository.insert(article));

            KnowledgebaseArticlePatch patch = KnowledgebaseArticlePatch.builder().content("").visible(false).updatedAt(LATER).build();
            Optional<KnowledgebaseArticle> updated = database.unitOfWork().write(() -> repository.update("article", patch));

            article.setContent("");
            article.setVisible(false);
            article.setUpdatedAt(LATER);
            assertThat(updated).contains(article);
            Optional<KnowledgebaseArticle> missing = database.unitOfWork().write(() -> repository.update("missing", patch));
            assertThat(missing).isEmpty();
        }
    }

    @SqlContractTest
    void reorderNeverTouchesAnotherCategory(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseArticleRepository repository = repository(database);
            insertCategory(database, "one");
            insertCategory(database, "two");
            database.unitOfWork().write(() -> {
                repository.insert(article("a", "one", 0, NOW, true));
                repository.insert(article("b", "one", 1, NOW, true));
                repository.insert(article("foreign", "two", 9, NOW, true));
            });

            database.unitOfWork().write(() -> repository.reorder("one", List.of("b", "foreign", "a")));

            assertThat(repository.findAll())
                .extracting(KnowledgebaseArticle::getId, KnowledgebaseArticle::getOrdinal)
                .containsExactly(tuple("b", 0), tuple("a", 2), tuple("foreign", 9));
        }
    }

    @SqlContractTest
    void deletesRemoveArticlesSinglyByCategoryAndWithTheirCategory(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseArticleRepository repository = repository(database);
            List.of("one", "two", "three").forEach(id -> insertCategory(database, id));
            database.unitOfWork().write(() -> {
                repository.insert(article("single", "one", 0, NOW, true));
                repository.insert(article("by-category", "two", 0, NOW, true));
                repository.insert(article("cascaded", "three", 0, NOW, true));
            });

            assertThat(database.unitOfWork().write(() -> repository.deleteById("single")).booleanValue()).isTrue();
            assertThat(database.unitOfWork().write(() -> repository.deleteByCategoryId("two")).longValue()).isEqualTo(1);
            database.unitOfWork().write(() -> database.dsl().deleteFrom(KNOWLEDGEBASE_CATEGORIES).execute());

            assertThat(repository.findAll()).isEmpty();
        }
    }

    @SqlContractTest
    void searchTakesWildcardsLiterallyAndSkipsHiddenArticles(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseArticleRepository repository = repository(database);
            insertCategory(database, "category");
            database.unitOfWork().write(() -> {
                repository.insert(withContent(article("percent", "category", 0, NOW, true), "100% sure"));
                repository.insert(withContent(article("underscore", "category", 1, NOW, true), "snake_case"));
                repository.insert(withContent(article("bang", "category", 2, NOW, true), "Hello!"));
                repository.insert(withContent(article("hidden", "category", 3, NOW, false), "100% snake_case Hello!"));
            });

            assertThat(ids(repository.searchVisible("%", 20))).containsExactly("percent");
            assertThat(ids(repository.searchVisible("_", 20))).containsExactly("underscore");
            assertThat(ids(repository.searchVisible("!", 20))).containsExactly("bang");
            assertThat(ids(repository.searchVisible("hELLO", 20))).containsExactly("bang");
            assertThat(ids(repository.searchVisible("TITLE UNDERSCORE", 20))).containsExactly("underscore");
            assertThat(repository.searchVisible("no such words", 20)).isEmpty();
        }
    }

    @SqlContractTest
    void searchFoldsUnicodeOnlyOnTheMysqlFamily(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseArticleRepository repository = repository(database);
            insertCategory(database, "category");
            database.unitOfWork().write(
                () -> repository.insert(withContent(article("accent", "category", 0, NOW, true), "École")));

            assertThat(ids(repository.searchVisible("ÉCOLE", 20))).containsExactly("accent");
            List<String> folded = target.dialect().isMysqlFamily() ? List.of("accent") : List.of();
            assertThat(ids(repository.searchVisible("école", 20))).isEqualTo(folded);
        }
    }

    @SqlContractTest
    void searchStopsAtItsLimitInDisplayOrder(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqKnowledgebaseArticleRepository repository = repository(database);
            insertCategory(database, "category");
            database.unitOfWork().write(() -> IntStream.range(0, 6)
                .forEach(index -> repository.insert(article("article-" + index, "category", 5 - index, NOW, true))));

            assertThat(ids(repository.searchVisible("title", 3))).containsExactly("article-5", "article-4", "article-3");
        }
    }

    private static JooqKnowledgebaseArticleRepository repository(SqlTestDatabase database) {
        return new JooqKnowledgebaseArticleRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent());
    }

    private static List<String> ids(List<KnowledgebaseArticle> articles) {
        return articles.stream().map(KnowledgebaseArticle::getId).toList();
    }

    private static KnowledgebaseArticle withContent(KnowledgebaseArticle article, String content) {
        article.setContent(content);
        return article;
    }

    private static KnowledgebaseArticle article(String id, String categoryId, int ordinal, Instant createdAt, boolean visible) {
        return KnowledgebaseArticle.builder()
            .id(id)
            .categoryId(categoryId)
            .title("Title " + id)
            .slug(id)
            .content("Body")
            .ordinal(ordinal)
            .isVisible(visible)
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
