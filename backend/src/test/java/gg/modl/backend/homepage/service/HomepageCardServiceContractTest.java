package gg.modl.backend.homepage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.homepage.data.HomepageCardAction;
import gg.modl.backend.homepage.data.HomepageCardEdit;
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
import gg.modl.proto.modl.v1.CreateCategoryRequest;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
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

            boolean changed = replace(database, services, List.of(
                category(null, "Rules", category.getId()),
                link(null, "Appeal", "/appeal")));

            assertThat(changed).isTrue();
            assertThat(services.cards().getAllCards(server))
                .extracting(HomepageCard::getTitle, HomepageCard::getOrdinal, HomepageCard::getCategoryId)
                .containsExactly(tuple("Rules", 0, category.getId()), tuple("Appeal", 1, null));
            List<EnrichedCardWithArticles> visible = services.cards().getVisibleCardsEnrichedWithArticles(server);
            assertThat(visible).extracting(EnrichedCardWithArticles::category).containsExactly(category, null);
            assertThat(visible.get(0).articles()).containsExactly(shown);
            assertThat(visible.get(1).articles()).isEmpty();
        }
    }

    @SqlContractTest
    void aReplacementUpdatesKeptCardsAddsNewOnesDropsTheRestAndFollowsTheGivenOrder(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Services services = Services.on(database);
            replace(database, services, List.of(link(null, "One", "/one"), link(null, "Two", "/two"), link(null, "Three", "/three")));
            List<HomepageCard> seeded = services.cards().getAllCards(server);
            String one = seeded.get(0).getId();
            String three = seeded.get(2).getId();

            boolean changed = replace(database, services, List.of(
                link(three, "Third", "https://example.com/three"), link(null, "New", "/new"), link(one, "One", "/one")));

            assertThat(changed).isTrue();
            List<HomepageCard> cards = services.cards().getAllCards(server);
            assertThat(cards).extracting(HomepageCard::getTitle, HomepageCard::getActionUrl, HomepageCard::getOrdinal)
                .containsExactly(tuple("Third", "https://example.com/three", 0), tuple("New", "/new", 1), tuple("One", "/one", 2));
            assertThat(cards.get(0).getId()).isEqualTo(three);
            assertThat(cards.get(2).getId()).isEqualTo(one);
            List<HomepageCardEdit> unchanged = cards.stream()
                .map(card -> link(card.getId(), card.getTitle(), card.getActionUrl()))
                .toList();
            assertThat(replace(database, services, unchanged)).isFalse();
        }
    }

    @SqlContractTest
    void unlinkingOrDeletingTheCategoryLeavesTheCardWithoutOne(DialectTarget target) {
        try (SqlTestDatabase database = SqlTestDatabase.start(target, dataDirectory, new RetryInjection())) {
            Services services = Services.on(database);
            KnowledgebaseCategory category = services.categories()
                .createCategory(server, CreateCategoryRequest.newBuilder().setName("Guides").build());
            replace(database, services, List.of(category(null, "Guides", category.getId()), category(null, "Guides", category.getId())));
            List<HomepageCard> cards = services.cards().getAllCards(server);
            String unlinked = cards.get(0).getId();
            String orphaned = cards.get(1).getId();

            replace(database, services, List.of(link(unlinked, "Guides", "/guides"), category(orphaned, "Guides", category.getId())));
            assertThat(services.categories().deleteCategory(server, category.getId())).isTrue();

            assertThat(services.cards().getAllCards(server)).extracting(HomepageCard::getCategoryId).containsOnlyNulls();
        }
    }

    private static boolean replace(SqlTestDatabase database, Services services, List<HomepageCardEdit> edits) {
        return database.unitOfWork().write(() -> services.cards().replaceWith(edits));
    }

    private static HomepageCardEdit link(@Nullable String id, String title, String url) {
        return new HomepageCardEdit(id, title, "", "", HomepageCardAction.LINK, url, "", true);
    }

    private static HomepageCardEdit category(@Nullable String id, String title, String categoryId) {
        return new HomepageCardEdit(id, title, "", "", HomepageCardAction.CATEGORY, "", categoryId, true);
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
