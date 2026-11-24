package gg.modl.backend.knowledgebase.service;

import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_ARTICLES;
import static gg.modl.backend.database.jooq.Tables.KNOWLEDGEBASE_CATEGORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import gg.modl.backend.database.TextLimits;
import gg.modl.backend.infrastructure.exception.ResourceNotFoundException;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.knowledgebase.repository.JooqKnowledgebaseArticleRepository;
import gg.modl.backend.knowledgebase.repository.JooqKnowledgebaseCategoryRepository;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.proto.modl.v1.CreateArticleRequest;
import gg.modl.proto.modl.v1.CreateCategoryRequest;
import gg.modl.proto.modl.v1.UpdateArticleRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class KnowledgebaseServiceContractTest {
    private static final int LONGEST_ACCEPTED_ARTICLE_TITLE = 256;
    private static final int LONGEST_ACCEPTED_CATEGORY_NAME = 128;

    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void theLongestAcceptedTitleIsStoredWithACappedUniqueSlug(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            String categoryId = categories(database)
                .createCategory(server, CreateCategoryRequest.newBuilder().setName("Guides").build()).getId();
            KnowledgebaseArticleService articles = articles(database);
            String title = "a".repeat(LONGEST_ACCEPTED_ARTICLE_TITLE);
            int limit = TextLimits.limit(KNOWLEDGEBASE_ARTICLES.SLUG);

            List<String> slugs = new ArrayList<>();
            for (int copy = 0; copy < 11; copy++) {
                KnowledgebaseArticle stored = articles.createArticle(server, categoryId,
                    CreateArticleRequest.newBuilder().setTitle(title).setContent("Body").build());
                assertThat(stored.getTitle()).isEqualTo(title);
                slugs.add(stored.getSlug());
            }

            assertThat(slugs).doesNotHaveDuplicates().allSatisfy(slug -> assertThat(slug).hasSize(limit));
            assertThat(slugs.getFirst()).isEqualTo("a".repeat(limit));
            assertThat(slugs.get(1)).endsWith("-1");
            assertThat(slugs.getLast()).endsWith("-10");
        }
    }

    @SqlContractTest
    void textThatGrowsWhileBeingSlugifiedStillFitsItsColumn(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            KnowledgebaseCategory category = categories(database).createCategory(server,
                CreateCategoryRequest.newBuilder().setName("Ⅷ".repeat(LONGEST_ACCEPTED_CATEGORY_NAME)).build());
            KnowledgebaseArticle article = articles(database).createArticle(server, category.getId(), CreateArticleRequest.newBuilder()
                .setTitle("Ⅷ".repeat(LONGEST_ACCEPTED_ARTICLE_TITLE))
                .setContent("Body")
                .build());

            assertThat(category.getSlug().codePointCount(0, category.getSlug().length()))
                .isLessThanOrEqualTo(TextLimits.limit(KNOWLEDGEBASE_CATEGORIES.SLUG));
            assertThat(article.getSlug().codePointCount(0, article.getSlug().length()))
                .isLessThanOrEqualTo(TextLimits.limit(KNOWLEDGEBASE_ARTICLES.SLUG));
        }
    }

    @SqlContractTest
    void renamingToALongTitleLeavesRoomForTheSuffix(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            String categoryId = categories(database)
                .createCategory(server, CreateCategoryRequest.newBuilder().setName("Guides").build()).getId();
            KnowledgebaseArticleService articles = articles(database);
            String title = "b".repeat(LONGEST_ACCEPTED_ARTICLE_TITLE);
            KnowledgebaseArticle holder = articles.createArticle(server, categoryId,
                CreateArticleRequest.newBuilder().setTitle(title).setContent("Body").build());
            KnowledgebaseArticle renamed = articles.createArticle(server, categoryId,
                CreateArticleRequest.newBuilder().setTitle("Short").setContent("Body").build());
            UpdateArticleRequest rename = UpdateArticleRequest.newBuilder().setTitle(title).build();

            assertThat(articles.updateArticle(server, holder.getId(), rename))
                .get().extracting(KnowledgebaseArticle::getSlug).isEqualTo(holder.getSlug());
            assertThat(articles.updateArticle(server, renamed.getId(), rename)).get().satisfies(article -> {
                assertThat(article.getSlug()).hasSize(TextLimits.limit(KNOWLEDGEBASE_ARTICLES.SLUG));
                assertThat(article.getSlug()).endsWith("-1");
            });
        }
    }

    @SqlContractTest
    void articlesAreNumberedWithinTheirCategoryOncePerRequest(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            String categoryId = categories(database)
                .createCategory(server, CreateCategoryRequest.newBuilder().setName("Guides").build()).getId();
            KnowledgebaseArticleService articles = articles(database);
            CreateArticleRequest request = CreateArticleRequest.newBuilder().setTitle("How to join").setContent("Body").build();

            articles.createArticle(server, categoryId, request);
            articles.createArticle(server, categoryId, request);

            assertThat(articles.getArticlesByCategory(server, categoryId))
                .extracting(KnowledgebaseArticle::getSlug, KnowledgebaseArticle::getOrdinal)
                .containsExactly(tuple("how-to-join", 0), tuple("how-to-join-1", 1));
        }
    }

    @SqlContractTest
    void anArticleForAMissingCategoryIsNotFound(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory)) {
            KnowledgebaseArticleService articles = articles(database);
            CreateArticleRequest request = CreateArticleRequest.newBuilder().setTitle("Lost").setContent("Body").build();

            assertThatThrownBy(() -> articles.createArticle(server, "missing", request))
                .isInstanceOf(ResourceNotFoundException.class);
            assertThat(database.dsl().fetchCount(KNOWLEDGEBASE_ARTICLES)).isZero();
        }
    }

    @SqlContractTest
    void deletingACategoryDeletesItsArticles(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            KnowledgebaseCategoryService categories = categories(database);
            String categoryId = categories.createCategory(server, CreateCategoryRequest.newBuilder().setName("News").build()).getId();
            articles(database).createArticle(server, categoryId,
                CreateArticleRequest.newBuilder().setTitle("Update").setContent("Body").build());

            assertThat(categories.deleteCategory(server, categoryId)).isTrue();

            assertThat(database.dsl().fetchCount(KNOWLEDGEBASE_ARTICLES)).isZero();
        }
    }

    private static KnowledgebaseCategoryService categories(SqlTestDatabase database) {
        return new KnowledgebaseCategoryService(
            new JooqKnowledgebaseCategoryRepository(database.dsl()), database.unitOfWork(), database.clock());
    }

    private static KnowledgebaseArticleService articles(SqlTestDatabase database) {
        return new KnowledgebaseArticleService(
            new JooqKnowledgebaseArticleRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent()),
            new JooqKnowledgebaseCategoryRepository(database.dsl()), database.unitOfWork(), database.clock());
    }
}
