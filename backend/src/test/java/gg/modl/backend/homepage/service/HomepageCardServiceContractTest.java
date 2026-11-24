package gg.modl.backend.homepage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.homepage.repository.JooqHomepageCardRepository;
import gg.modl.backend.homepage.service.HomepageCardService.EnrichedCardWithArticles;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.knowledgebase.repository.JooqKnowledgebaseArticleRepository;
import gg.modl.backend.knowledgebase.repository.JooqKnowledgebaseCategoryRepository;
import gg.modl.backend.knowledgebase.service.KnowledgebaseArticleService;
import gg.modl.backend.knowledgebase.service.KnowledgebaseCategoryService;
import gg.modl.backend.server.data.Server;
import gg.modl.backend.support.TestServers;
import gg.modl.backend.support.sql.DialectTarget;
import gg.modl.backend.support.sql.RetryInjection;
import gg.modl.backend.support.sql.SqlContractTest;
import gg.modl.backend.support.sql.SqlTestDatabase;
import gg.modl.proto.modl.v1.CreateArticleRequest;
import gg.modl.proto.modl.v1.CreateCardRequest;
import gg.modl.proto.modl.v1.CreateCategoryRequest;
import gg.modl.proto.modl.v1.UpdateCardRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.io.TempDir;

class HomepageCardServiceContractTest {
    private final Server server = TestServers.server("server", "admin@example.com");

    @TempDir
    Path dataDirectory;

    @SqlContractTest
    void cardsShowTheirCategoryWithItsVisibleArticlesAndSurviveARetry(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Services services = Services.on(database);
            KnowledgebaseCategory category = services.categories()
                .createCategory(server, CreateCategoryRequest.newBuilder().setName("Rules").build());
            KnowledgebaseArticle shown = services.articles().createArticle(server, category.getId(),
                CreateArticleRequest.newBuilder().setTitle("Chat").setContent("Be nice").build());
            services.articles().createArticle(server, category.getId(),
                CreateArticleRequest.newBuilder().setTitle("Draft").setContent("Later").setIsVisible(false).build());

            HomepageCard dropdown = services.cards().createCard(server, CreateCardRequest.newBuilder()
                .setTitle("Rules").setActionType("category_dropdown").setCategoryId(category.getId()).build());
            HomepageCard link = services.cards().createCard(server, CreateCardRequest.newBuilder()
                .setTitle("Appeal").setActionType("url").setActionUrl("/appeal").setCategoryId("").build());

            assertThat(link.getCategoryId()).isNull();
            assertThat(services.cards().getAllCards(server))
                .extracting(HomepageCard::getId, HomepageCard::getOrdinal)
                .containsExactly(tuple(dropdown.getId(), 0), tuple(link.getId(), 1));
            List<EnrichedCardWithArticles> visible = services.cards().getVisibleCardsEnrichedWithArticles(server);
            assertThat(visible).extracting(EnrichedCardWithArticles::category).containsExactly(category, null);
            assertThat(visible.get(0).articles()).containsExactly(shown);
            assertThat(visible.get(1).articles()).isEmpty();
        }
    }

    @SqlContractTest
    void unlinkingOrDeletingTheCategoryLeavesTheCardWithoutOne(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Services services = Services.on(database);
            KnowledgebaseCategory category = services.categories()
                .createCategory(server, CreateCategoryRequest.newBuilder().setName("Guides").build());
            CreateCardRequest request = CreateCardRequest.newBuilder().setTitle("Guides").setCategoryId(category.getId()).build();
            HomepageCard unlinked = services.cards().createCard(server, request);
            HomepageCard orphaned = services.cards().createCard(server, request);

            assertThat(services.cards().updateCard(server, unlinked.getId(), UpdateCardRequest.newBuilder().setCategoryId("").build()))
                .get().extracting(HomepageCard::getCategoryId).isNull();
            assertThat(services.categories().deleteCategory(server, category.getId())).isTrue();

            assertThat(services.cards().getCardById(server, orphaned.getId())).get().extracting(HomepageCard::getCategoryId).isNull();
        }
    }

    private record Services(HomepageCardService cards, KnowledgebaseCategoryService categories, KnowledgebaseArticleService articles) {
        static Services on(SqlTestDatabase database) {
            JooqKnowledgebaseCategoryRepository categoryRows = new JooqKnowledgebaseCategoryRepository(database.dsl());
            KnowledgebaseCategoryService categories =
                new KnowledgebaseCategoryService(categoryRows, database.unitOfWork(), database.clock());
            KnowledgebaseArticleService articles = new KnowledgebaseArticleService(
                new JooqKnowledgebaseArticleRepository(database.dsl(), database.unitOfWork(), database.insertIfAbsent()),
                categoryRows, database.unitOfWork(), database.clock());
            HomepageCardService cards = new HomepageCardService(new JooqHomepageCardRepository(database.dsl()),
                categories, articles, database.unitOfWork(), database.clock());
            return new Services(cards, categories, articles);
        }
    }
}
