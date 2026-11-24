package gg.modl.backend.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gg.modl.backend.homepage.data.HomepageCard;
import gg.modl.backend.homepage.repository.HomepageCardRepository;
import gg.modl.backend.instance.InstanceTestKit;
import gg.modl.backend.knowledgebase.data.KnowledgebaseArticle;
import gg.modl.backend.knowledgebase.data.KnowledgebaseCategory;
import gg.modl.backend.knowledgebase.data.StarterArticles;
import gg.modl.backend.knowledgebase.data.StarterArticles.StarterArticle;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseArticleRepository;
import gg.modl.backend.knowledgebase.repository.KnowledgebaseCategoryRepository;
import gg.modl.backend.role.service.RoleService;
import gg.modl.backend.settings.service.PunishmentTypeEditor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class ServerProvisioningServiceTest {
    private static final List<String> SEEDED_TYPES =
        List.of("aiModerationSettings", "ticketForms", "quickResponses", "general", "ticketLabels");

    @TempDir
    Path dataDirectory;

    private InstanceTestKit kit;
    private HomepageCardRepository cards;
    private KnowledgebaseCategoryRepository categories;
    private KnowledgebaseArticleRepository articles;
    private ServerProvisioningService service;

    @BeforeEach
    void setUp() {
        kit = InstanceTestKit.startWithInjectedRetries(dataDirectory);
        cards = mock(HomepageCardRepository.class);
        categories = mock(KnowledgebaseCategoryRepository.class);
        articles = mock(KnowledgebaseArticleRepository.class);
        service = new ServerProvisioningService(kit.settingsRepository(), categories, articles, cards, mock(PunishmentTypeEditor.class),
            mock(RoleService.class), kit.unitOfWork(), kit.database().jsonColumns(), kit.clock());
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void theFirstRunSeedsEverySettingsRowAtVersionOneWithoutAServerName() {
        service.provision(kit.server());

        assertThat(SEEDED_TYPES).allSatisfy(type -> assertThat(kit.version(type)).isEqualTo(1));
        assertThat(kit.stored("general")).doesNotContainKey("serverDisplayName");
    }

    @Test
    void aLaterRunNeverOverwritesWhatWasEdited() {
        kit.store("ticketLabels", Map.of("labels", List.of()));

        service.provision(kit.server());
        service.provision(kit.server());

        assertThat(kit.stored("ticketLabels")).isEqualTo(Map.of("labels", List.of()));
        assertThat(kit.version("ticketLabels")).isEqualTo(1);
    }

    @Test
    void seededCardsPointAtTheSeededCategoriesAndUseTheClock() {
        KnowledgebaseCategory rules = KnowledgebaseCategory.builder().id("rules-id").slug("rules-policies").build();
        when(categories.findAllOrdered()).thenReturn(List.of(rules));

        service.provision(kit.server());

        verify(categories, atLeastOnce()).insert(any());
        ArgumentCaptor<HomepageCard> inserted = ArgumentCaptor.forClass(HomepageCard.class);
        verify(cards, atLeastOnce()).insert(inserted.capture());
        assertThat(inserted.getAllValues()).allSatisfy(card -> {
            assertThat(card.getId()).hasSize(16);
            assertThat(card.getCreatedAt()).isEqualTo(kit.clock().instant());
        });
        assertThat(inserted.getAllValues()).extracting(HomepageCard::getCategoryId).contains("rules-id");
    }

    @Test
    void seededCardsAndQuickResponsesUsePlainSentenceCaseWithoutFiller() {
        service.provision(kit.server());

        ArgumentCaptor<HomepageCard> inserted = ArgumentCaptor.forClass(HomepageCard.class);
        verify(cards, atLeastOnce()).insert(inserted.capture());
        List<HomepageCard> seeded = inserted.getAllValues().subList(0, 6);
        assertThat(seeded).extracting(HomepageCard::getTitle)
            .containsExactly("Appeal a punishment", "Apply for staff", "Contact staff", "Rules", "Guides", "News");
        assertThat(seeded).extracting(HomepageCard::getDescription).containsOnlyNulls();
        assertThat(seeded).extracting(HomepageCard::getActionButtonText)
            .containsExactly("Appeal", "Apply", "Open a ticket", null, null, null);
        ArgumentCaptor<KnowledgebaseCategory> seededCategories = ArgumentCaptor.forClass(KnowledgebaseCategory.class);
        verify(categories, atLeastOnce()).insert(seededCategories.capture());
        assertThat(seededCategories.getAllValues().subList(0, 3)).extracting(KnowledgebaseCategory::getName)
            .containsExactly("Rules", "Guides", "News");

        List<String> names = quickResponseNames(kit.stored("quickResponses"));
        assertThat(names).contains("Accept", "No violation", "Ask for evidence", "Pardon", "Uphold", "Chat reports")
            .noneMatch(name -> name.contains(" - "))
            .allMatch(name -> name.equals(name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT)));
    }

    @Test
    void theStarterArticlesAreSeededIntoTheirDefaultCategoriesInOrder() {
        service.provision(kit.server());

        ArgumentCaptor<KnowledgebaseCategory> seededCategories = ArgumentCaptor.forClass(KnowledgebaseCategory.class);
        verify(categories, atLeastOnce()).insert(seededCategories.capture());
        Map<String, String> slugByCategoryId = new HashMap<>();
        seededCategories.getAllValues().forEach(category -> slugByCategoryId.put(category.getId(), category.getSlug()));
        ArgumentCaptor<KnowledgebaseArticle> seeded = ArgumentCaptor.forClass(KnowledgebaseArticle.class);
        verify(articles, atLeastOnce()).insert(seeded.capture());
        List<KnowledgebaseArticle> attempts = seeded.getAllValues();
        List<KnowledgebaseArticle> committed = attempts.subList(attempts.size() - StarterArticles.ALL.size(), attempts.size());

        assertThat(committed).extracting(KnowledgebaseArticle::getSlug)
            .containsExactlyElementsOf(StarterArticles.ALL.stream().map(StarterArticle::slug).toList());
        assertThat(committed).extracting(article -> slugByCategoryId.get(article.getCategoryId()))
            .containsExactlyElementsOf(StarterArticles.ALL.stream().map(StarterArticle::categorySlug).toList());
        assertThat(committed).extracting(KnowledgebaseArticle::getOrdinal).containsExactly(0, 0, 1, 0);
        assertThat(committed).allSatisfy(article -> {
            assertThat(article.isVisible()).isTrue();
            assertThat(article.isStarter()).isTrue();
            assertThat(article.getId()).hasSize(16);
            assertThat(article.getCreatedAt()).isEqualTo(kit.clock().instant());
        });
    }

    @Test
    void noStarterArticleIsSeededWhenTheKnowledgebaseAlreadyHasCategories() {
        when(categories.hasAny()).thenReturn(true);

        service.provision(kit.server());

        verify(articles, never()).insert(any());
    }

    private static List<String> quickResponseNames(Map<String, Object> quickResponses) {
        List<String> names = new ArrayList<>();
        for (Object category : (List<?>) quickResponses.get("categories")) {
            Map<?, ?> fields = (Map<?, ?>) category;
            names.add(String.valueOf(fields.get("name")));
            for (Object action : (List<?>) fields.get("actions")) {
                names.add(String.valueOf(((Map<?, ?>) action).get("name")));
            }
        }
        return names;
    }
}
