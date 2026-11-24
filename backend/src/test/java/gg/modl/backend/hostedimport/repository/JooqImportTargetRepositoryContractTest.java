package gg.modl.backend.hostedimport.repository;

import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;
import static org.assertj.core.api.Assertions.assertThat;

import gg.modl.backend.database.jooq.tables.records.KnowledgebaseCategoriesRecord;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.data.StarterArticles;
import gg.modl.backend.knowledgebase.data.StarterArticles.StarterArticle;
import gg.modl.backend.knowledgebase.repository.JooqKnowledgebaseArticleRepository;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseArticlePatch;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class JooqImportTargetRepositoryContractTest {
    private static final Instant NOW = SqlTestDatabase.START;
    private static final String CATEGORY = "category";

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void seededStarterArticlesDoNotCountAsArticles(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            JooqImportTargetRepository targets = targets(database);
            assertThat(targets.hasArticlesBesidesStarters()).isFalse();

            insertCategory(database);
            insertStarters(database, StarterArticles.ALL);

            assertThat(targets.hasArticlesBesidesStarters()).isFalse();
        }
    }

    @SqlContractTest
    void aStarterArticleWithChangedTextFromAnOlderReleaseStillDoesNotCount(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            insertCategory(database);
            StarterArticle first = StarterArticles.ALL.getFirst();
            insertStarters(database, List.of(new StarterArticle(first.categorySlug(), first.title(), first.slug(), "Older text")));

            assertThat(targets(database).hasArticlesBesidesStarters()).isFalse();
        }
    }

    @SqlContractTest
    void anEditedStarterArticleCountsAsAnArticle(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            insertCategory(database);
            insertStarters(database, StarterArticles.ALL);
            String first = StarterArticles.ALL.getFirst().slug();

            database.unitOfWork().write(() -> articles(database).update(first,
                KnowledgebaseArticlePatch.builder().content("Edited").updatedAt(NOW.plusSeconds(1)).build()));

            assertThat(targets(database).hasArticlesBesidesStarters()).isTrue();
        }
    }

    @SqlContractTest
    void hidingAStarterArticleKeepsItAStarter(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            insertCategory(database);
            insertStarters(database, StarterArticles.ALL);
            String first = StarterArticles.ALL.getFirst().slug();

            database.unitOfWork().write(() -> articles(database).update(first,
                KnowledgebaseArticlePatch.builder().visible(false).updatedAt(NOW.plusSeconds(1)).build()));

            assertThat(targets(database).hasArticlesBesidesStarters()).isFalse();
        }
    }

    @SqlContractTest
    void anArticleThatWasNotSeededCountsEvenUnderAStarterSlug(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            insertCategory(database);
            KnowledgebaseArticle own = article(StarterArticles.ALL.getFirst());
            own.setStarter(false);
            database.unitOfWork().write(() -> articles(database).insert(own));

            assertThat(targets(database).hasArticlesBesidesStarters()).isTrue();
        }
    }

    private static JooqImportTargetRepository targets(SqlTestDatabase database) {
        return new JooqImportTargetRepository(database.dsl(), database.unitOfWork());
    }

    private static JooqKnowledgebaseArticleRepository articles(SqlTestDatabase database) {
        return new JooqKnowledgebaseArticleRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent());
    }

    private static void insertStarters(SqlTestDatabase database, List<StarterArticle> starters) {
        JooqKnowledgebaseArticleRepository articles = articles(database);
        database.unitOfWork().write(() -> starters.forEach(starter -> articles.insert(article(starter))));
    }

    private static KnowledgebaseArticle article(StarterArticle starter) {
        return KnowledgebaseArticle.builder()
            .id(starter.slug())
            .categoryId(CATEGORY)
            .title(starter.title())
            .slug(starter.slug())
            .content(starter.content())
            .isVisible(true)
            .starter(true)
            .createdAt(NOW)
            .updatedAt(NOW)
            .build();
    }

    private static void insertCategory(SqlTestDatabase database) {
        KnowledgebaseCategoriesRecord row = new KnowledgebaseCategoriesRecord();
        row.setId(CATEGORY);
        row.setName(CATEGORY);
        row.setSlug(CATEGORY);
        row.setCreatedAt(NOW);
        row.setUpdatedAt(NOW);
        database.unitOfWork().write(() -> database.dsl().insertInto(KNOWLEDGEBASE_CATEGORIES).set(row).execute());
    }
}
